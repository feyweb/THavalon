package com.thavalon.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThavalonPropertiesTest {

    @Test
    @DisplayName("defaults fill in for anything unset")
    void defaultsApply() {
        ThavalonProperties p = new ThavalonProperties(null, null, null, null);
        assertThat(p.dataDir()).isEqualTo("./data");
        assertThat(p.gameTtl()).isEqualTo(Duration.ofHours(6));
        assertThat(p.auditUnlockAfter()).isEqualTo(Duration.ofHours(4));
        assertThat(p.auditRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(p.auditRetention()).isGreaterThan(p.auditUnlockAfter());
    }

    /**
     * Retention shorter than the seal deletes every trail before it can be read. The failure is
     * invisible at runtime — the reader cannot distinguish a swept trail from a game that was
     * never dealt — so it has to be caught at startup.
     */
    @Test
    @DisplayName("retention shorter than the unlock window is refused at startup")
    void retentionShorterThanUnlockIsRefused() {
        assertThatThrownBy(() -> new ThavalonProperties(
                "./data", Duration.ofHours(6), Duration.ofHours(4), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audit-retention")
                .hasMessageContaining("audit-unlock-after");
    }

    @Test
    @DisplayName("retention exactly equal to the unlock window is refused too")
    void equalDurationsAreRefused() {
        // Equal leaves a zero-width window in which the trail is both unlocked and expired.
        assertThatThrownBy(() -> new ThavalonProperties(
                "./data", Duration.ofHours(6), Duration.ofHours(4), Duration.ofHours(4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero unlock window is fine — tests use it to read the audit immediately")
    void zeroUnlockIsAllowed() {
        assertThatCode(() -> new ThavalonProperties(
                "./data", Duration.ofHours(6), Duration.ZERO, Duration.ofDays(30)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("any retention longer than the unlock window is accepted")
    void longerRetentionIsAccepted() {
        assertThatCode(() -> new ThavalonProperties(
                "./data", Duration.ofHours(6), Duration.ofHours(4), Duration.ofHours(5)))
                .doesNotThrowAnyException();
    }
}
