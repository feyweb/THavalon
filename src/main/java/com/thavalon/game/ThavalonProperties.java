package com.thavalon.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param dataDir          where per-game JSON snapshots and audit trails live, so a restart
 *                         mid-game does not lose roles
 * @param gameTtl          how long a game survives after its last activity before being swept
 * @param auditUnlockAfter how long after the deal a game's audit opens. Until then the trail
 *                         is sealed, so it cannot be read mid-game.
 * @param auditRetention   how long a finished game's audit trail is kept before being deleted.
 *                         Unlike a game snapshot (swept at {@code gameTtl}), the trail is what
 *                         powers the Past games list, so it outlives the game by this much.
 */
@ConfigurationProperties(prefix = "thavalon")
public record ThavalonProperties(String dataDir, Duration gameTtl, Duration auditUnlockAfter,
                                 Duration auditRetention) {

    public ThavalonProperties {
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "./data";
        }
        if (gameTtl == null) {
            gameTtl = Duration.ofHours(6);
        }
        if (auditUnlockAfter == null) {
            auditUnlockAfter = Duration.ofHours(4);
        }
        if (auditRetention == null) {
            auditRetention = Duration.ofDays(30);
        }

        // Two timers act on the same file: a trail is sealed until auditUnlockAfter, and deleted
        // at auditRetention. Inverted, every trail is destroyed before it ever becomes readable
        // and games silently never reach Past games — with no error anywhere, because the reader
        // cannot tell a swept trail from a game that was never dealt. Fail at startup instead,
        // where the cause is obvious.
        if (!auditRetention.minus(auditUnlockAfter).isPositive()) {
            throw new IllegalArgumentException(
                    "thavalon.audit-retention (%s) must be longer than thavalon.audit-unlock-after (%s), "
                            .formatted(auditRetention, auditUnlockAfter)
                            + "or audit trails are deleted before they ever unlock");
        }
    }
}
