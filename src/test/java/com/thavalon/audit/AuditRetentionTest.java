package com.thavalon.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thavalon.game.ThavalonProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing that bounds the Past games list: trails older than the retention window are
 * swept, and everything inside it is kept.
 */
class AuditRetentionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private AuditLog logOver(Path dir) {
        // 30-day retention; unlock and TTL are irrelevant to the sweep.
        ThavalonProperties props = new ThavalonProperties(
                dir.toString(), Duration.ofHours(6), Duration.ofHours(4), Duration.ofDays(30));
        return new AuditLog(MAPPER, props);
    }

    @Test
    @DisplayName("a trail past the retention window is swept; a recent one is kept")
    void sweepsOnlyExpiredTrails(@TempDir Path dir) throws Exception {
        AuditLog audit = logOver(dir);
        Path auditDir = dir.resolve("audit");

        // Two trails: one written long ago, one just now.
        audit.record("OLD-20260101-000000-AAAA", "OLD", AuditEventType.GAME_STARTED, "Host",
                Map.of("playerCount", 5));
        audit.record("NEW-20260801-000000-BBBB", "NEW", AuditEventType.GAME_STARTED, "Host",
                Map.of("playerCount", 5));

        Path oldTrail = auditDir.resolve("OLD-20260101-000000-AAAA.jsonl");
        Path newTrail = auditDir.resolve("NEW-20260801-000000-BBBB.jsonl");
        assertThat(oldTrail).exists();
        assertThat(newTrail).exists();

        // Back-date the old trail well past the 30-day window; the sweep reads last-modified time.
        Files.setLastModifiedTime(oldTrail, FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        audit.sweepOldTrails();

        assertThat(oldTrail).as("expired trail is deleted").doesNotExist();
        assertThat(newTrail).as("recent trail is kept").exists();
    }

    @Test
    @DisplayName("nothing is swept when every trail is within the window")
    void keepsEverythingWithinWindow(@TempDir Path dir) throws Exception {
        AuditLog audit = logOver(dir);
        audit.record("FRIDAY-20260801-000000-CCCC", "FRIDAY", AuditEventType.GAME_STARTED, "Host",
                Map.of("playerCount", 7));

        audit.sweepOldTrails();

        assertThat(dir.resolve("audit").resolve("FRIDAY-20260801-000000-CCCC.jsonl")).exists();
    }
}
