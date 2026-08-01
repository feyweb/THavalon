package com.thavalon.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Games live in memory and are mirrored to one JSON file each.
 *
 * <p>The snapshot exists so a redeploy or a crash partway through a game does not wipe everyone's
 * roles — players reconnect with the token in their browser and get the same card back. Volume is
 * tiny (a handful of concurrent games, ten players each), so a file per game beats a database.
 */
@Component
public class GameStore {

    private static final Logger log = LoggerFactory.getLogger(GameStore.class);

    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path dataDir;
    private final ThavalonProperties properties;

    public GameStore(ObjectMapper mapper, ThavalonProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.dataDir = Path.of(properties.dataDir());
    }

    @PostConstruct
    void loadFromDisk() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dataDir, e);
        }

        try (Stream<Path> files = Files.list(dataDir)) {
            List<Path> snapshots = files.filter(p -> p.toString().endsWith(".json")).toList();
            for (Path snapshot : snapshots) {
                try {
                    Game game = mapper.readValue(snapshot.toFile(), Game.class);
                    if (game.getId() != null) {
                        backfill(game);
                        games.put(game.getId(), game);
                    }
                } catch (IOException e) {
                    // A corrupt snapshot must not stop the app from starting.
                    log.warn("Ignoring unreadable game snapshot {}: {}", snapshot, e.getMessage());
                }
            }
            log.info("Restored {} game(s) from {}", games.size(), dataDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not list {}: {}", dataDir, e.getMessage());
        }
    }

    /**
     * Fills in fields added after a snapshot was written. Without this, a game restored from an
     * older file carries a null {@code auditKey} and every subsequent audit write rejects it,
     * leaving the game permanently unjoinable.
     */
    private void backfill(Game game) {
        if (game.getAuditKey() == null || game.getAuditKey().isBlank()) {
            Instant created = game.getCreatedAt() == null ? Instant.now() : game.getCreatedAt();
            game.setAuditKey(game.getId() + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC).format(created) + "-OLD1");
            log.info("Backfilled audit key for restored game {}", game.getId());
        }
    }

    public Optional<Game> find(String gameId) {
        return gameId == null
                ? Optional.empty()
                : Optional.ofNullable(games.get(gameId.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean exists(String gameId) {
        return gameId != null && games.containsKey(gameId.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Claims an ID atomically. A plain exists-then-save leaves a window in which two hosts both
     * see the ID as free and the second silently overwrites the first, taking their players
     * with it.
     *
     * @return false if the ID was already taken
     */
    public boolean reserve(Game game) {
        game.touch();
        if (games.putIfAbsent(game.getId(), game) != null) {
            return false;
        }
        writeSnapshot(game);
        return true;
    }

    public Collection<Game> all() {
        return games.values();
    }

    public void save(Game game) {
        game.touch();
        games.put(game.getId(), game);
        writeSnapshot(game);
    }

    public void delete(String gameId) {
        games.remove(gameId);
        try {
            Files.deleteIfExists(snapshotPath(gameId));
        } catch (IOException e) {
            log.warn("Could not delete snapshot for {}: {}", gameId, e.getMessage());
        }
    }

    /** Write via a temp file and an atomic move, so a crash never leaves a half-written game. */
    private void writeSnapshot(Game game) {
        Path target = snapshotPath(game.getId());
        try {
            Path tmp = Files.createTempFile(dataDir, game.getId(), ".tmp");
            mapper.writeValue(tmp.toFile(), game);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // The in-memory copy is authoritative; losing the snapshot only costs restart-resilience.
            log.warn("Could not persist game {}: {}", game.getId(), e.getMessage());
        }
    }

    private Path snapshotPath(String gameId) {
        return dataDir.resolve(gameId + ".json");
    }

    @Scheduled(fixedDelayString = "PT15M")
    void sweepExpiredGames() {
        Instant cutoff = Instant.now().minus(properties.gameTtl());
        List<String> expired = games.values().stream()
                .filter(g -> g.getUpdatedAt().isBefore(cutoff))
                .map(Game::getId)
                .toList();
        expired.forEach(this::delete);
        if (!expired.isEmpty()) {
            log.info("Swept {} expired game(s)", expired.size());
        }
    }
}
