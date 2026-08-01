package com.thavalon.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thavalon.audit.AuditLog;
import com.thavalon.domain.Dealer;
import com.thavalon.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Restart behaviour, exercised by building a second store over the same directory — which is
 * exactly what a redeploy does. No test-only hooks in the production classes.
 */
class GameStorePersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private GameStore storeOver(Path dir) {
        GameStore store = new GameStore(MAPPER, props(dir));
        store.loadFromDisk();
        return store;
    }

    private ThavalonProperties props(Path dir) {
        return new ThavalonProperties(dir.toString(), Duration.ofHours(6), Duration.ZERO,
                Duration.ofDays(30));
    }

    private GameService serviceOver(Path dir, GameStore store) {
        return new GameService(store, new Dealer(), new AuditLog(MAPPER, props(dir)), props(dir));
    }

    @Test
    @DisplayName("a dealt game reloads from disk with every role and token intact")
    @SuppressWarnings("unchecked")
    void dealtGameSurvivesRestart(@TempDir Path dir) {
        GameStore first = storeOver(dir);
        GameService service = serviceOver(dir, first);

        GameService.Joined host = service.createGame("Host", "RELOAD");
        List<String> tokens = new java.util.ArrayList<>(List.of(host.playerToken()));
        for (int i = 1; i < 7; i++) {
            tokens.add(service.join("RELOAD", "P" + i).playerToken());
        }
        service.start("RELOAD", host.playerToken());

        Map<String, Role> before = first.find("RELOAD").orElseThrow().getPlayers().stream()
                .collect(Collectors.toMap(Player::getToken, Player::getRole));

        // A completely separate store, reading only what is on disk.
        GameStore reloaded = storeOver(dir);
        Game game = reloaded.find("RELOAD").orElseThrow();

        assertThat(game.getState()).isEqualTo(GameState.DEALT);
        assertThat(game.getPlayers()).hasSize(7);
        Map<String, Role> after = game.getPlayers().stream()
                .collect(Collectors.toMap(Player::getToken, Player::getRole));
        assertThat(after).as("same token still yields the same role").isEqualTo(before);
        assertThat(game.getPlayers()).allSatisfy(p -> {
            assertThat(p.getRole()).isNotNull();
            assertThat(p.getInfo()).isNotNull();
        });
        assertThat(game.getHostToken()).as("host token survives the reload")
                .isEqualTo(host.playerToken());
        assertThat(game.playerByToken(host.playerToken())).isPresent()
                .get().extracting(Player::getName).isEqualTo("Host");
    }

    /**
     * Regression: snapshots written before {@code auditKey} existed reload with a null key, and
     * every later audit write rejects it — the game becomes permanently unjoinable, surfacing as
     * "400 Invalid audit key". The store must repair such a snapshot on load.
     */
    @Test
    @DisplayName("a snapshot lacking an audit key is repaired on load, not left broken")
    void snapshotWithoutAuditKeyIsRepaired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("OLDGAME.json"), """
                {
                  "id": "OLDGAME",
                  "hostToken": "tok-host",
                  "state": "LOBBY",
                  "players": [{"name": "Host", "token": "tok-host"}],
                  "createdAt": "2026-07-01T10:00:00Z",
                  "updatedAt": "2026-07-01T10:00:00Z"
                }
                """);

        GameStore store = storeOver(dir);
        Game game = store.find("OLDGAME").orElseThrow();
        assertThat(game.getAuditKey()).as("backfilled rather than left null").isNotBlank();

        GameService service = serviceOver(dir, store);
        assertThatCode(() -> service.join("OLDGAME", "Joiner"))
                .as("the restored game is joinable").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unreadable snapshot is skipped rather than blocking startup")
    void corruptSnapshotDoesNotBlockStartup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("BROKEN.json"), "{ this is not json");
        Files.writeString(dir.resolve("GOOD.json"), """
                {"id":"GOOD","hostToken":"t","state":"LOBBY",
                 "auditKey":"GOOD-20260801-000000-AAAA",
                 "players":[{"name":"Host","token":"t"}],
                 "createdAt":"2026-08-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}
                """);

        GameStore store = storeOver(dir);
        assertThat(store.find("GOOD")).isPresent();
        assertThat(store.find("BROKEN")).isEmpty();
    }

    @Test
    @DisplayName("game IDs resolve case-insensitively after a reload too")
    void reloadedGamesResolveCaseInsensitively(@TempDir Path dir) {
        GameStore first = storeOver(dir);
        serviceOver(dir, first).createGame("Host", "MixedCase");

        GameStore reloaded = storeOver(dir);
        assertThat(reloaded.find("mixedcase")).isPresent();
        assertThat(reloaded.find("MIXEDCASE")).isPresent();
        assertThat(reloaded.exists("  mixedcase  ")).isTrue();
    }
}
