package com.thavalon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleInfoTest {

    /** A ten-player table containing every role, so each rule can be checked in one place. */
    private static final Seat MERLIN = new Seat("Merl", Role.MERLIN);
    private static final Seat PERCIVAL = new Seat("Perc", Role.PERCIVAL);
    private static final Seat TRISTAN = new Seat("Tris", Role.TRISTAN);
    private static final Seat ISEULT = new Seat("Isla", Role.ISEULT);
    private static final Seat LANCELOT = new Seat("Lanc", Role.LANCELOT);
    private static final Seat TITANIA = new Seat("Tita", Role.TITANIA);
    private static final Seat MORDRED = new Seat("Mord", Role.MORDRED);
    private static final Seat MORGANA = new Seat("Morg", Role.MORGANA);
    private static final Seat AGRAVAINE = new Seat("Agra", Role.AGRAVAINE);
    private static final Seat COLGREVANCE = new Seat("Colg", Role.COLGREVANCE);

    private static final List<Seat> TABLE = List.of(
            MERLIN, PERCIVAL, TRISTAN, ISEULT, LANCELOT, TITANIA,
            MORDRED, MORGANA, AGRAVAINE, COLGREVANCE);

    @Test
    @DisplayName("Merlin sees Evil except Mordred, plus Lancelot, and never Titania")
    void merlinSeesEvilExceptMordredPlusLancelot() {
        assertThat(RoleInfo.baseInfo(MERLIN, TABLE)).containsExactlyInAnyOrder(
                "Lanc is Evil.", "Morg is Evil.", "Agra is Evil.", "Colg is Evil.");
        // Mordred is hidden; Titania is Good and so is invisible to Merlin.
        assertThat(RoleInfo.baseInfo(MERLIN, TABLE))
                .noneMatch(line -> line.startsWith("Mord") || line.startsWith("Tita"));
    }

    @Test
    @DisplayName("Percival sees Merlin and Morgana without being able to tell them apart")
    void percivalSeesMerlinAndMorgana() {
        assertThat(RoleInfo.baseInfo(PERCIVAL, TABLE)).containsExactlyInAnyOrder(
                "Merl is Merlin or Morgana.", "Morg is Merlin or Morgana.");
    }

    @Test
    @DisplayName("the lovers see each other and nobody else")
    void loversSeeEachOther() {
        assertThat(RoleInfo.baseInfo(TRISTAN, TABLE)).containsExactly("Isla is Iseult.");
        assertThat(RoleInfo.baseInfo(ISEULT, TABLE)).containsExactly("Tris is Tristan.");
    }

    @Test
    @DisplayName("Lancelot and Titania receive no information")
    void abilityRolesSeeNothing() {
        assertThat(RoleInfo.baseInfo(LANCELOT, TABLE)).isEmpty();
        assertThat(RoleInfo.baseInfo(TITANIA, TABLE)).isEmpty();
    }

    @Test
    @DisplayName("Evil see each other and Titania, but never Colgrevance")
    void evilSeeEachOtherAndTitaniaButNotColgrevance() {
        assertThat(RoleInfo.baseInfo(MORDRED, TABLE)).containsExactlyInAnyOrder(
                "Tita is Evil.", "Morg is Evil.", "Agra is Evil.");
        assertThat(RoleInfo.baseInfo(MORGANA, TABLE)).containsExactlyInAnyOrder(
                "Tita is Evil.", "Mord is Evil.", "Agra is Evil.");
        // Colgrevance is absent, and no Evil player sees themselves.
        assertThat(RoleInfo.baseInfo(AGRAVAINE, TABLE))
                .noneMatch(line -> line.startsWith("Colg") || line.startsWith("Agra"));
    }

    @Test
    @DisplayName("Colgrevance sees every Evil player's exact role, and is not fooled by Titania")
    void colgrevanceSeesExactRoles() {
        assertThat(RoleInfo.baseInfo(COLGREVANCE, TABLE)).containsExactlyInAnyOrder(
                "Mord is Mordred", "Morg is Morgana", "Agra is Agravaine");
        assertThat(RoleInfo.baseInfo(COLGREVANCE, TABLE))
                .noneMatch(line -> line.startsWith("Tita"));
    }

    @Test
    @DisplayName("Arthur learns the Good roles present, excluding themselves")
    void arthurLearnsGoodRoles() {
        Seat arthur = new Seat("Arth", Role.ARTHUR);
        List<Seat> table = List.of(arthur, MERLIN, PERCIVAL, LANCELOT, MORDRED, MORGANA, AGRAVAINE);
        assertThat(RoleInfo.baseInfo(arthur, table))
                .containsExactlyInAnyOrder("Merlin", "Percival", "Lancelot");
    }

    @Test
    @DisplayName("Nimue learns every role in the game, Good and Evil, excluding themselves")
    void nimueLearnsAllRoles() {
        Seat nimue = new Seat("Nim", Role.NIMUE);
        List<Seat> table = List.of(nimue, MERLIN, LANCELOT, MORDRED, MORGANA);
        assertThat(RoleInfo.baseInfo(nimue, table))
                .containsExactlyInAnyOrder("Merlin", "Lancelot", "Mordred", "Morgana");
    }

    @Test
    @DisplayName("Evil are warned about Colgrevance and Titania, but Colgrevance is not")
    void evilNoticesAreTargetedCorrectly() {
        assertThat(RoleInfo.notices(MORDRED, TABLE, false))
                .containsExactly(RoleInfo.COLGREVANCE_NOTICE, RoleInfo.TITANIA_NOTICE);
        assertThat(RoleInfo.notices(COLGREVANCE, TABLE, false)).isEmpty();
        assertThat(RoleInfo.notices(MERLIN, TABLE, false)).isEmpty();
    }

    @Test
    @DisplayName("notices are omitted when the roles they describe are absent")
    void noticesOmittedWhenRolesAbsent() {
        List<Seat> plainTable = List.of(MERLIN, PERCIVAL, LANCELOT, MORDRED, MORGANA);
        assertThat(RoleInfo.notices(MORDRED, plainTable, false)).isEmpty();
    }

    @Test
    @DisplayName("the Assassin notice is appended for whoever holds the kill")
    void assassinNoticeAppended() {
        assertThat(RoleInfo.notices(MORGANA, TABLE, true)).endsWith(RoleInfo.ASSASSIN_NOTICE);
    }

    @Test
    @DisplayName("every role produces information without throwing")
    void everyRoleIsHandled() {
        for (Role role : Role.values()) {
            Seat seat = new Seat("Solo", role);
            assertThat(RoleInfo.baseInfo(seat, List.of(seat))).isNotNull();
        }
    }
}
