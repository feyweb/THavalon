package com.thavalon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleTableTest {

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("pools are large enough to fill every seat")
    void poolsAreLargeEnough(int count) {
        RoleTable.Config config = RoleTable.forPlayers(count);
        assertThat(config.goodPool()).hasSizeGreaterThanOrEqualTo(config.goodCount());
        assertThat(config.evilPool()).hasSizeGreaterThanOrEqualTo(config.evilCount());
        assertThat(config.goodCount() + config.evilCount()).isEqualTo(count);
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("pools contain no duplicates and are correctly teamed")
    void poolsAreWellFormed(int count) {
        RoleTable.Config config = RoleTable.forPlayers(count);
        assertThat(config.goodPool()).doesNotHaveDuplicates().allMatch(Role::isGood);
        assertThat(config.evilPool()).doesNotHaveDuplicates().allMatch(Role::isEvil);
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("both lovers are always eligible together, so the pair is always achievable")
    void bothLoversAreAlwaysEligible(int count) {
        RoleTable.Config config = RoleTable.forPlayers(count);
        assertThat(config.goodPool()).contains(Role.TRISTAN, Role.ISEULT);
        // The upgrade-to-a-pair repair branch needs room for both.
        assertThat(config.goodCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Evil counts match the reference implementation exactly, including 6 and 9")
    void evilCountsMatchReferenceImplementation() {
        // Transcribed from thavalon.py:91-96 — num_evil is 2 below 7 players, 3 below 9, else 4.
        // 6 players (2 Evil, a 33% share) and 9 players (4 Evil, where standard Avalon gives 3)
        // are preserved deliberately. See RoleTable's javadoc.
        assertThat(RoleTable.forPlayers(5).evilCount()).isEqualTo(2);
        assertThat(RoleTable.forPlayers(6).evilCount()).isEqualTo(2);
        assertThat(RoleTable.forPlayers(7).evilCount()).isEqualTo(3);
        assertThat(RoleTable.forPlayers(8).evilCount()).isEqualTo(3);
        assertThat(RoleTable.forPlayers(9).evilCount()).isEqualTo(4);
        assertThat(RoleTable.forPlayers(10).evilCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("count-restricted roles appear only at their supported counts")
    void countRestrictedRolesAreGated() {
        // Nimue: 5 players only (thavalon.py:105-106)
        assertThat(RoleTable.forPlayers(5).goodPool()).contains(Role.NIMUE);
        for (int n = 6; n <= 10; n++) {
            assertThat(RoleTable.forPlayers(n).goodPool()).doesNotContain(Role.NIMUE);
        }

        // Arthur and Titania: 7+ players (thavalon.py:109-111)
        for (int n = 5; n <= 6; n++) {
            assertThat(RoleTable.forPlayers(n).goodPool()).doesNotContain(Role.ARTHUR, Role.TITANIA);
        }
        for (int n = 7; n <= 10; n++) {
            assertThat(RoleTable.forPlayers(n).goodPool()).contains(Role.ARTHUR, Role.TITANIA);
        }

        // Agravaine: 8+ players (thavalon.py:114-115)
        for (int n = 5; n <= 7; n++) {
            assertThat(RoleTable.forPlayers(n).evilPool()).doesNotContain(Role.AGRAVAINE);
        }
        for (int n = 8; n <= 10; n++) {
            assertThat(RoleTable.forPlayers(n).evilPool()).contains(Role.AGRAVAINE);
        }

        // Colgrevance: 10 players only (thavalon.py:118-119)
        for (int n = 5; n <= 9; n++) {
            assertThat(RoleTable.forPlayers(n).evilPool()).doesNotContain(Role.COLGREVANCE);
        }
        assertThat(RoleTable.forPlayers(10).evilPool()).contains(Role.COLGREVANCE);
    }

    @Test
    @DisplayName("every count has a non-Colgrevance Evil available for the Assassin")
    void assassinCandidateAlwaysExists() {
        for (int n = 5; n <= 10; n++) {
            RoleTable.Config config = RoleTable.forPlayers(n);
            long nonColgrevance = config.evilPool().stream()
                    .filter(r -> r != Role.COLGREVANCE).count();
            // Even if Colgrevance is drawn, enough other Evil roles remain to fill the seats.
            assertThat(nonColgrevance).isGreaterThanOrEqualTo(config.evilCount() - 1L);
        }
    }

    @Test
    @DisplayName("unsupported counts are rejected")
    void unsupportedCountsRejected() {
        assertThat(RoleTable.isSupported(4)).isFalse();
        assertThat(RoleTable.isSupported(11)).isFalse();
        assertThatThrownBy(() -> RoleTable.forPlayers(4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
