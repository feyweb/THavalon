package com.thavalon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DealerTest {

    private static final int STRESS_ITERATIONS = 10_000;

    private final Dealer dealer = new Dealer(new Random(20260801L));

    private static List<String> players(int count) {
        return IntStream.range(0, count).mapToObj(i -> "P" + i).toList();
    }

    /**
     * The regression that motivated this rewrite. The reference implementation crashes on its
     * lone-lover repair path: the replacement pool is empty at 6 and 10 players, and on Python
     * 3.11+ the {@code random.sample(set, 1)} call fails at every count. Measured 4 crashes in
     * 40 ten-player runs of thavalon.py on Python 3.13.
     */
    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("deals without throwing, ten thousand times, at every supported count")
    void dealsWithoutThrowing(int count) {
        List<String> names = players(count);
        Dealer stressDealer = new Dealer(new Random(count));
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            Deal deal = stressDealer.deal(names);
            assertThat(deal.assignments()).hasSize(count);
        }
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("every player gets exactly one role, and team sizes match the table")
    void teamSizesMatchTable(int count) {
        RoleTable.Config config = RoleTable.forPlayers(count);
        Dealer d = new Dealer(new Random(count));

        for (int i = 0; i < 500; i++) {
            Deal deal = d.deal(players(count));

            assertThat(deal.assignments()).extracting(Assignment::playerName)
                    .containsExactlyInAnyOrderElementsOf(players(count));
            assertThat(deal.rolesInGame()).doesNotHaveDuplicates();
            assertThat(deal.assignments()).filteredOn(a -> a.team() == Team.EVIL)
                    .hasSize(config.evilCount());
            assertThat(deal.assignments()).filteredOn(a -> a.team() == Team.GOOD)
                    .hasSize(config.goodCount());
        }
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("only roles eligible at this player count are dealt")
    void onlyEligibleRolesAreDealt(int count) {
        RoleTable.Config config = RoleTable.forPlayers(count);
        Set<Role> eligible = new java.util.HashSet<>(config.goodPool());
        eligible.addAll(config.evilPool());

        Dealer d = new Dealer(new Random(count));
        for (int i = 0; i < 1_000; i++) {
            assertThat(d.deal(players(count)).rolesInGame()).allSatisfy(role ->
                    assertThat(eligible).contains(role));
        }
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("lovers always appear as a pair, never alone")
    void loversAreNeverAlone(int count) {
        Dealer d = new Dealer(new Random(count));
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            long lovers = d.deal(players(count)).rolesInGame().stream().filter(Role::isLover).count();
            assertThat(lovers).isIn(0L, 2L);
        }
    }

    /**
     * At 6 and 10 players the lovers are unavoidable: there are only five non-lover Good roles,
     * and those counts draw 4-of-5 and 6-of-7 Good roles respectively, so at least one lover is
     * always in hand and the repair upgrades it to the pair. These are precisely the two counts
     * where the reference implementation's replacement pool is empty and it crashes.
     */
    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {6, 10})
    @DisplayName("lovers are mandatory at the counts with no spare Good role")
    void loversAreMandatoryAtConstrainedCounts(int count) {
        Dealer d = new Dealer(new Random(count));
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            long lovers = d.deal(players(count)).rolesInGame().stream().filter(Role::isLover).count();
            assertThat(lovers).isEqualTo(2L);
        }
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 7, 8, 9})
    @DisplayName("both lover repair branches are reachable where a spare Good role exists")
    void bothLoverRepairBranchesAreReachable(int count) {
        Dealer d = new Dealer(new Random(count));
        boolean sawPair = false;
        boolean sawNone = false;
        for (int i = 0; i < STRESS_ITERATIONS && !(sawPair && sawNone); i++) {
            long lovers = d.deal(players(count)).rolesInGame().stream().filter(Role::isLover).count();
            sawPair |= lovers == 2;
            sawNone |= lovers == 0;
        }
        assertThat(sawPair).as("games with both lovers").isTrue();
        assertThat(sawNone).as("games with neither lover").isTrue();
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {5, 6, 7, 8, 9, 10})
    @DisplayName("exactly one Assassin, always Evil")
    void assassinIsExactlyOneEvil(int count) {
        Dealer d = new Dealer(new Random(count));
        for (int i = 0; i < STRESS_ITERATIONS; i++) {
            Deal deal = d.deal(players(count));
            List<Assignment> assassins = deal.assignments().stream().filter(Assignment::assassin).toList();

            assertThat(assassins).hasSize(1);
            assertThat(assassins.getFirst().team()).isEqualTo(Team.EVIL);
            assertThat(assassins.getFirst().info()).contains(RoleInfo.ASSASSIN_NOTICE);
        }
    }

    /**
     * Colgrevance is an eligible Assassin, as in the reference implementation. The choice is
     * uniform across Evil, so with four Evil at ten players she takes the kill in a quarter of
     * the games she appears in.
     */
    @Test
    @DisplayName("every Evil role, Colgrevance included, holds the kill equally often")
    void assassinIsUniformAcrossEvil() {
        Dealer d = new Dealer(new Random(1010L));
        int games = 20_000;
        var assassinCount = new java.util.EnumMap<Role, Integer>(Role.class);
        var inPlayCount = new java.util.EnumMap<Role, Integer>(Role.class);

        for (int i = 0; i < games; i++) {
            Deal deal = d.deal(players(10));
            deal.assignments().stream().filter(a -> a.role().isEvil()).forEach(a -> {
                inPlayCount.merge(a.role(), 1, Integer::sum);
                if (a.assassin()) {
                    assassinCount.merge(a.role(), 1, Integer::sum);
                }
            });
        }

        assertThat(assassinCount).containsKey(Role.COLGREVANCE);
        // Four Evil seats, chosen uniformly, so each Evil role in play takes it about 1 in 4.
        assertThat(inPlayCount).allSatisfy((role, inPlay) ->
                assertThat(assassinCount.getOrDefault(role, 0) / (double) inPlay)
                        .as("share of games in which %s is the Assassin", role)
                        .isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.02)));
    }

    @Test
    @DisplayName("consecutive deals differ — no caching, no reused seed")
    void consecutiveDealsDiffer() {
        Dealer secure = new Dealer();
        List<String> names = players(10);
        Set<String> fingerprints = IntStream.range(0, 100)
                .mapToObj(i -> secure.deal(names).assignments().stream()
                        .map(a -> a.playerName() + ":" + a.role())
                        .sorted()
                        .collect(Collectors.joining(",")))
                .collect(Collectors.toSet());
        assertThat(fingerprints).hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("each player is equally likely to be Evil")
    void evilIsDistributedEvenly() {
        List<String> names = players(10);
        var evilCounts = new java.util.HashMap<String, Integer>();
        Dealer d = new Dealer(new Random(7L));
        int rounds = 20_000;
        for (int i = 0; i < rounds; i++) {
            d.deal(names).assignments().stream()
                    .filter(a -> a.team() == Team.EVIL)
                    .forEach(a -> evilCounts.merge(a.playerName(), 1, Integer::sum));
        }
        // 4 Evil of 10 players => expected 40% each. Generous tolerance for a sampling test.
        assertThat(evilCounts).allSatisfy((name, count) ->
                assertThat(count / (double) rounds).isBetween(0.36, 0.44));
    }

    @Test
    @DisplayName("rejects unsupported player counts, blanks and duplicates")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> dealer.deal(players(4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 to 10");
        assertThatThrownBy(() -> dealer.deal(players(11)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 to 10");
        assertThatThrownBy(() -> dealer.deal(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> withBlank = new ArrayList<>(players(4));
        withBlank.add("   ");
        assertThatThrownBy(() -> dealer.deal(withBlank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");

        assertThatThrownBy(() -> dealer.deal(List.of("Ann", "bob", "Cat", "Dan", "BOB")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }
}
