package com.thavalon.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thavalon.audit.AuditLog;
import com.thavalon.domain.Dealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the audit reports when a trail is missing.
 *
 * <p>A trail can vanish for reasons other than misconfiguration — a failed write, a disk problem,
 * someone tidying the data directory. The reader must not present that as "this game was never
 * dealt", because it is indistinguishable from the truth in the response body.
 */
class AuditAvailabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private ThavalonProperties props(Path dir) {
        // Unlock immediately so the seal is not what is under test.
        return new ThavalonProperties(dir.toString(), Duration.ofHours(6), Duration.ZERO,
                Duration.ofDays(30));
    }

    private record Fixture(GameService service, GameStore store, Path auditDir) {
    }

    private Fixture fixture(Path dir) {
        GameStore store = new GameStore(MAPPER, props(dir));
        store.loadFromDisk();
        GameService service = new GameService(store, new Dealer(),
                new AuditLog(MAPPER, props(dir)), props(dir));
        return new Fixture(service, store, dir.resolve("audit"));
    }

    private String dealGame(GameService service, String id) {
        GameService.Joined host = service.createGame("Host", id);
        for (int i = 1; i < 5; i++) {
            service.join(id, "P" + i);
        }
        service.start(id, host.playerToken());
        return host.playerToken();
    }

    @Test
    @DisplayName("a dealt game whose trail is gone reports 410, not a cheerful empty 200")
    void missingTrailForDealtGameIsGone(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        dealGame(f.service(), "VANISHED");

        // Sanity: the trail is readable while it exists.
        assertThat(f.service().auditFor("VANISHED").roles()).hasSize(5);

        String auditKey = f.store().find("VANISHED").orElseThrow().getAuditKey();
        Files.delete(f.auditDir().resolve(auditKey + ".jsonl"));

        assertThatThrownBy(() -> f.service().auditFor("VANISHED"))
                .isInstanceOf(GameException.class)
                .satisfies(e -> {
                    GameException g = (GameException) e;
                    assertThat(g.status()).isEqualTo(HttpStatus.GONE);
                    assertThat(g.code()).isEqualTo("AUDIT_UNAVAILABLE");
                });
    }

    @Test
    @DisplayName("a lobby that never dealt still reports an empty trail, not an error")
    void lobbyThatNeverDealtIsNotAnError(@TempDir Path dir) {
        Fixture f = fixture(dir);
        f.service().createGame("Host", "JUSTALOBBY");

        GameService.AuditView view = f.service().auditFor("JUSTALOBBY");
        assertThat(view.startedAt()).isNull();
        assertThat(view.roles()).isEmpty();
        assertThat(view.events()).isNotEmpty();      // GAME_CREATED is there
        assertThat(view.state()).isEqualTo(GameState.LOBBY);
    }

    @Test
    @DisplayName("a swept game with no trail left is not found, rather than reported as undealt")
    void sweptGameWithNoTrailIsNotFound(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        dealGame(f.service(), "LONGGONE");

        String auditKey = f.store().find("LONGGONE").orElseThrow().getAuditKey();
        f.store().delete("LONGGONE");                                  // game swept
        Files.delete(f.auditDir().resolve(auditKey + ".jsonl"));       // trail past retention

        assertThatThrownBy(() -> f.service().auditFor("LONGGONE"))
                .isInstanceOf(GameException.class)
                .satisfies(e -> assertThat(((GameException) e).code()).isEqualTo("GAME_NOT_FOUND"));
    }
}
