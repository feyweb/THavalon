package com.thavalon.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thavalon.game.ThavalonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An append-only trail of what happened in each game, one JSON object per line.
 *
 * <p>Unlike game snapshots, audit files are <em>not</em> swept when a game expires — the whole
 * point is to be able to look back at a game that has finished. They are a few hundred bytes each.
 *
 * <p>Writes never fail a request: if the disk is unavailable the event is logged and dropped,
 * because losing an audit line is strictly better than refusing to deal a game.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final ObjectMapper mapper;
    private final Path auditDir;
    private final Object writeLock = new Object();

    public AuditLog(ObjectMapper mapper, ThavalonProperties properties) {
        this.mapper = mapper;
        this.auditDir = Path.of(properties.dataDir()).resolve("audit");
        // Created here rather than in a lifecycle hook so the object is usable the moment it
        // exists, whether Spring built it or a test did.
        try {
            Files.createDirectories(auditDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create audit directory " + auditDir, e);
        }
    }

    /**
     * @param auditKey filename stem for this game's trail — the game ID plus a creation
     *                 timestamp, so a reused ID does not append to an older game's trail
     * @param gameId   the ID players type, recorded on every event
     */
    public void record(String auditKey, String gameId, AuditEventType type, String actor,
                       Map<String, Object> detail) {
        AuditEvent event = new AuditEvent(Instant.now(), gameId, type, actor, detail);
        String line;
        try {
            line = mapper.writeValueAsString(event);
        } catch (IOException e) {
            log.warn("Could not serialise audit event for {}: {}", gameId, e.getMessage());
            return;
        }

        // Logged in full, roles included, so `docker logs` is a complete audit on its own and
        // reading the JSONL files is optional.
        log.info("audit {}", line);

        try {
            synchronized (writeLock) {
                Files.writeString(pathFor(auditKey), line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("Could not append audit event for {}: {}", auditKey, e.getMessage());
        }
    }

    /**
     * The most recent trail for a game ID, for looking up a game that has already been swept.
     * IDs are reusable, so this picks the newest matching trail.
     */
    public Optional<String> latestKeyFor(String gameId) {
        try (Stream<Path> files = Files.list(auditDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".jsonl"))
                    .map(f -> f.substring(0, f.length() - ".jsonl".length()))
                    .filter(key -> key.equals(gameId) || key.startsWith(gameId + "-"))
                    .max(java.util.Comparator.naturalOrder());
        } catch (IOException e) {
            log.warn("Could not search audit directory: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The trail for one audit key, oldest first. Empty if there is no such file. */
    public List<AuditEvent> read(String auditKey) {
        Path path = pathFor(auditKey);
        if (!Files.exists(path)) {
            return List.of();
        }
        List<AuditEvent> events = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    events.add(mapper.readValue(line, AuditEvent.class));
                } catch (IOException e) {
                    // One malformed line must not hide the rest of the trail.
                    log.warn("Skipping unreadable audit line in {}: {}", auditKey, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not read audit for {}: {}", auditKey, e.getMessage());
        }
        return events;
    }

    /**
     * @param gameId      the ID players typed to join
     * @param auditKey    filename stem, unique per game even when an ID is reused
     * @param createdAt   when the lobby was opened
     * @param startedAt   when roles were dealt, or null if the game never started
     * @param lastEventAt the most recent activity of any kind
     * @param players     names in join order, as recorded at deal time
     * @param roles       player name to dealt role, empty if the game never started
     */
    public record Summary(
            String gameId,
            String auditKey,
            Instant createdAt,
            Instant startedAt,
            Instant lastEventAt,
            int playerCount,
            boolean started,
            List<String> players,
            Map<String, Object> roles) {
    }

    /** Every game with a trail on disk, newest activity first. */
    public List<Summary> index() {
        try (Stream<Path> files = Files.list(auditDir)) {
            return files.filter(p -> p.toString().endsWith(".jsonl"))
                    .map(p -> p.getFileName().toString().replace(".jsonl", ""))
                    .map(this::summarise)
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparing(Summary::lastEventAt).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list audit directory: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Summary summarise(String auditKey) {
        List<AuditEvent> events = read(auditKey);
        if (events.isEmpty()) {
            return null;
        }
        AuditEvent start = events.stream()
                .filter(e -> e.type() == AuditEventType.GAME_STARTED)
                .findFirst().orElse(null);

        Map<String, Object> roles = start == null
                ? Map.of()
                : (Map<String, Object>) start.detail().getOrDefault("roles", Map.of());

        return new Summary(
                events.getFirst().gameId(),
                auditKey,
                events.getFirst().at(),
                start == null ? null : start.at(),
                events.getLast().at(),
                roles.isEmpty() ? countJoined(events) : roles.size(),
                start != null,
                List.copyOf(roles.keySet()),
                roles);
    }

    private int countJoined(List<AuditEvent> events) {
        long joined = events.stream()
                .filter(e -> e.type() == AuditEventType.GAME_CREATED || e.type() == AuditEventType.PLAYER_JOINED)
                .count();
        long left = events.stream().filter(e -> e.type() == AuditEventType.PLAYER_LEFT).count();
        return (int) Math.max(0, joined - left);
    }

    private Path pathFor(String auditKey) {
        // Keys are built internally, but never construct a path from input that has not been
        // checked — this rules out separators and traversal outright.
        // Up to: 12-char game ID + "-" + 15-char timestamp + "-" + 4-char suffix.
        if (auditKey == null || !auditKey.matches("[A-Z0-9][A-Z0-9-]{2,47}")) {
            throw new IllegalArgumentException("Invalid audit key");
        }
        return auditDir.resolve(auditKey + ".jsonl");
    }
}
