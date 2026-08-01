package com.thavalon.game;

import com.thavalon.audit.AuditEvent;
import com.thavalon.audit.AuditEventType;
import com.thavalon.audit.AuditLog;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import com.thavalon.domain.Assignment;
import com.thavalon.domain.Deal;
import com.thavalon.domain.Dealer;
import com.thavalon.domain.RoleTable;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Service
public class GameService {

    /**
     * Game IDs are generated in upper case from an alphabet that is unambiguous read aloud or off
     * a screen — no O/0, no I/1. Lookups are case-insensitive and tolerate surrounding whitespace,
     * so players can type them however they like; see {@link #require(String)}.
     */
    private static final String ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ID_LENGTH = 4;
    private static final int MAX_ID_ATTEMPTS = 100;
    private static final int MAX_NAME_LENGTH = 20;

    /** Letters, digits and hyphens; 3 to 12 characters; must not start with a hyphen. */
    private static final java.util.regex.Pattern VALID_GAME_ID =
            java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9-]{2,11}");

    private final GameStore store;
    private final Dealer dealer;
    private final AuditLog audit;
    private final ThavalonProperties properties;
    private final SecureRandom random = new SecureRandom();

    public GameService(GameStore store, Dealer dealer, AuditLog audit, ThavalonProperties properties) {
        this.store = store;
        this.dealer = dealer;
        this.audit = audit;
        this.properties = properties;
    }

    public record Joined(String gameId, String playerToken, String name, boolean host) {
    }

    /**
     * @param requestedId an ID chosen by the host, or blank for a generated one
     */
    public Joined createGame(String hostName, String requestedId) {
        String name = validName(hostName);
        boolean chosen = requestedId != null && !requestedId.isBlank();

        // Claim the ID atomically. Checking then saving would let two hosts creating "FRIDAY" at
        // the same moment both succeed, the second overwriting the first and its players.
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            Game game = new Game(resolveGameId(requestedId), null);
            game.setAuditKey(newAuditKey(game.getId()));

            Player host = new Player(name, newToken());
            game.setHostToken(host.getToken());
            game.getPlayers().add(host);

            if (store.reserve(game)) {
                record(game, AuditEventType.GAME_CREATED, host.getName(), Map.of());
                return new Joined(game.getId(), host.getToken(), host.getName(), true);
            }
            // A host-chosen ID has no alternative to fall back to; a generated one can retry.
            if (chosen) {
                throw GameException.gameIdTaken(game.getId());
            }
        }
        throw new IllegalStateException("Could not allocate a free game ID");
    }

    public Joined join(String gameId, String playerName) {
        String name = validName(playerName);
        Game game = require(gameId);

        synchronized (game) {
            if (game.getState() != GameState.LOBBY) {
                throw GameException.alreadyStarted();
            }
            if (game.getPlayers().size() >= RoleTable.MAX_PLAYERS) {
                throw GameException.gameFull(RoleTable.MAX_PLAYERS);
            }
            if (game.hasName(name)) {
                throw GameException.duplicateName(name);
            }

            Player player = new Player(name, newToken());
            game.getPlayers().add(player);
            store.save(game);

            record(game, AuditEventType.PLAYER_JOINED, player.getName(),
                    Map.of("playerCount", game.getPlayers().size()));
            return new Joined(game.getId(), player.getToken(), player.getName(), false);
        }
    }

    /**
     * Leave the lobby — the escape hatch for a mistyped name. Host duty passes to the next
     * player, and an emptied game is deleted outright rather than left to expire.
     */
    public void leave(String gameId, String token) {
        Game game = require(gameId);

        synchronized (game) {
            if (game.getState() != GameState.LOBBY) {
                throw GameException.alreadyStarted();
            }
            Player player = game.playerByToken(token).orElseThrow(GameException::badToken);
            game.getPlayers().remove(player);
            record(game, AuditEventType.PLAYER_LEFT, player.getName(),
                    Map.of("playerCount", game.getPlayers().size()));

            if (game.getPlayers().isEmpty()) {
                store.delete(game.getId());
                return;
            }
            if (game.isHost(token)) {
                Player newHost = game.getPlayers().getFirst();
                game.setHostToken(newHost.getToken());
                record(game, AuditEventType.HOST_TRANSFERRED, newHost.getName(),
                        Map.of("from", player.getName()));
            }
            store.save(game);
        }
    }

    public Game start(String gameId, String token) {
        Game game = require(gameId);

        synchronized (game) {
            if (!game.isHost(token)) {
                throw GameException.notHost();
            }
            if (game.getState() != GameState.LOBBY) {
                throw GameException.alreadyStarted();
            }

            int count = game.getPlayers().size();
            if (count < RoleTable.MIN_PLAYERS) {
                throw GameException.notEnoughPlayers(count, RoleTable.MIN_PLAYERS);
            }

            Deal deal = dealer.deal(game.getPlayers().stream().map(Player::getName).toList());
            Map<String, Assignment> byName = deal.assignments().stream()
                    .collect(java.util.stream.Collectors.toMap(Assignment::playerName, Function.identity()));

            for (Player player : game.getPlayers()) {
                Assignment assignment = byName.get(player.getName());
                player.setRole(assignment.role());
                player.setAssassin(assignment.assassin());
                player.setInfo(assignment.info());
            }
            game.setState(GameState.DEALT);
            store.save(game);

            // The dealt roles go into the audit under "roles", sealed until the game is over.
            Map<String, Object> roles = new LinkedHashMap<>();
            game.getPlayers().forEach(p -> roles.put(p.getName(), p.getRole().displayName()));
            record(game, AuditEventType.GAME_STARTED,
                    game.host().map(Player::getName).orElse(null),
                    Map.of("playerCount", count, "roles", roles));
            return game;
        }
    }

    /**
     * The caller's own player record, resolved from their private token. The first time a player
     * opens their card after the deal is audited, so the host can tell at a glance whether
     * everyone has actually looked.
     */
    public Player me(String gameId, String token) {
        Game game = require(gameId);
        Player player = game.playerByToken(token).orElseThrow(GameException::badToken);

        if (game.getState() != GameState.LOBBY && !player.isRoleViewed()) {
            synchronized (game) {
                if (!player.isRoleViewed()) {
                    player.setRoleViewed(true);
                    store.save(game);
                    record(game, AuditEventType.ROLE_VIEWED, player.getName(), Map.of());
                }
            }
        }
        return player;
    }

    /**
     * @param state  null once the game itself has been swept; audit trails outlive their games
     * @param roles  who was dealt what, in join order. Empty if the game never started.
     */
    public record AuditView(
            String gameId,
            GameState state,
            Instant startedAt,
            Map<String, Object> roles,
            List<AuditEvent> events) {
    }

    /**
     * A game's audit trail, including who was dealt which role.
     *
     * <p>Nothing here tracks missions, so the server cannot observe a game finishing. Rather than
     * guess, the trail is simply sealed for {@code thavalon.audit-unlock-after} from the moment
     * of the deal — long enough that a game in progress can never be read, and short enough that
     * the roles are there when someone goes looking afterwards.
     *
     * <p>There is deliberately no way to open it early. A password or a host override would be a
     * lever a player could pull mid-game; a clock is not.
     *
     * <p>A game that never started has nothing to protect and is never sealed.
     */
    public AuditView auditFor(String gameId) {
        String id = canonical(gameId);
        Game game = store.find(id).orElse(null);

        // A live game knows its own trail; otherwise fall back to the newest one under this ID,
        // since IDs are reusable and an old game may have been swept.
        String auditKey = game != null
                ? game.getAuditKey()
                : audit.latestKeyFor(id).orElse(null);

        List<AuditEvent> events = auditKey == null ? List.of() : audit.read(auditKey);

        if (game == null && events.isEmpty()) {
            throw GameException.notFound(id);
        }

        AuditEvent dealt = events.stream()
                .filter(e -> e.type() == AuditEventType.GAME_STARTED)
                .findFirst()
                .orElse(null);

        if (dealt != null && !isUnsealed(game, dealt)) {
            throw GameException.auditSealed(dealt.at().plus(properties.auditUnlockAfter()));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> roles = dealt == null
                ? Map.of()
                : (Map<String, Object>) dealt.detail().getOrDefault("roles", Map.of());

        return new AuditView(
                id,
                game == null ? null : game.getState(),
                dealt == null ? null : dealt.at(),
                roles,
                events);
    }

    private boolean isUnsealed(Game game, AuditEvent dealt) {
        // A swept game is by definition long finished; otherwise it is purely the clock.
        return game == null
                || !Instant.now().isBefore(dealt.at().plus(properties.auditUnlockAfter()));
    }

    /** Every game whose audit has opened, newest first. Sealed games are omitted. */
    public List<AuditLog.Summary> auditIndex() {
        return audit.index().stream().filter(this::isOpen).toList();
    }

    private boolean isOpen(AuditLog.Summary summary) {
        if (!summary.started()) {
            return true;                                   // nothing dealt, nothing to protect
        }
        Game game = store.find(summary.gameId()).orElse(null);
        // Only the game that owns this trail can seal it; an older trail under a reused ID is done.
        boolean ownsTrail = game != null && summary.auditKey().equals(game.getAuditKey());
        return !ownsTrail
                || !Instant.now().isBefore(summary.startedAt().plus(properties.auditUnlockAfter()));
    }

    public Game get(String gameId) {
        return require(gameId);
    }

    private void record(Game game, AuditEventType type, String actor, Map<String, Object> detail) {
        // GameStore backfills this on load, but never let a missing key turn an ordinary action
        // into an error — the audit is a side effect, not the point of the request.
        if (game.getAuditKey() == null || game.getAuditKey().isBlank()) {
            game.setAuditKey(newAuditKey(game.getId()));
        }
        audit.record(game.getAuditKey(), game.getId(), type, actor, detail);
    }

    private Game require(String gameId) {
        String id = canonical(gameId);
        return store.find(id).orElseThrow(() -> GameException.notFound(id));
    }

    /**
     * IDs are matched case-insensitively and tolerate surrounding whitespace.
     *
     * <p>{@link Locale#ROOT} is deliberate: a default-locale uppercase would map "i" to "İ" on a
     * Turkish-locale host, which then fails the ID pattern and the audit key check.
     */
    private static String canonical(String gameId) {
        return gameId == null ? "" : gameId.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Honours the host's chosen ID, falling back to a generated one when they leave it blank.
     * Only live games reserve an ID — once a game is swept, "FRIDAY-NIGHT" is free again.
     */
    private String resolveGameId(String requestedId) {
        if (requestedId == null || requestedId.isBlank()) {
            return generatedGameId();
        }
        String id = canonical(requestedId);
        if (!VALID_GAME_ID.matcher(id).matches()) {
            throw GameException.invalidGameId();
        }
        if (store.exists(id)) {
            throw GameException.gameIdTaken(id);
        }
        return id;
    }

    /**
     * Host-chosen IDs get reused week after week, so the audit filename carries a creation
     * timestamp. Without it, every "FRIDAY-NIGHT" would append to one ever-growing trail.
     *
     * <p>The timestamp alone is not enough — two games under the same ID created within the same
     * second would still collide and interleave — so a short random suffix guarantees uniqueness.
     * The timestamp leads so that keys still sort chronologically.
     */
    private String newAuditKey(String gameId) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return gameId + "-" + stamp + "-" + suffix;
    }

    private String validName(String raw) {
        if (raw == null) {
            throw GameException.invalidName();
        }
        // Collapse whitespace and drop control characters so names render predictably.
        String name = raw.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw GameException.invalidName();
        }
        return name;
    }

    private String generatedGameId() {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
            }
            String candidate = id.toString();
            if (!store.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a free game ID");
    }

    private String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
