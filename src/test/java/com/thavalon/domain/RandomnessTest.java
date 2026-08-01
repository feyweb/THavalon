package com.thavalon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that consecutive games are independent.
 *
 * <p>The bar is not "a player never repeats". Independent deals <em>must</em> produce repeats at
 * the chance rate — a dealer that never repeated would be leaking information, because last
 * game's Evil would be known-Good this game. So each observed repeat rate is compared against the
 * rate implied by that role's own frequency. Materially fewer repeats than chance is as much a
 * failure as materially more.
 *
 * <p>Player count is fixed at ten so every seat exists in every game and the arithmetic is clean.
 */
class RandomnessTest {

    private static final int GAMES = 2_000;
    private static final int PLAYERS = 10;

    @Test
    @DisplayName("consecutive deals are independent — repeats occur at the chance rate")
    void consecutiveDealsAreIndependent() {
        Dealer dealer = new Dealer();
        List<String> names = IntStream.range(0, PLAYERS).mapToObj(i -> "P" + i).toList();

        List<Map<String, Role>> history = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) {
            history.add(dealer.deal(names).assignments().stream()
                    .collect(Collectors.toMap(Assignment::playerName, Assignment::role)));
        }

        // Observed marginals, used to derive what chance alone would produce.
        Map<Role, Integer> roleCounts = new EnumMap<>(Role.class);
        int evilSeats = 0;
        for (Map<String, Role> game : history) {
            for (Role role : game.values()) {
                roleCounts.merge(role, 1, Integer::sum);
                if (role.isEvil()) evilSeats++;
            }
        }
        int seats = GAMES * PLAYERS;
        double pEvil = evilSeats / (double) seats;
        double expectedSameTeam = pEvil * pEvil + (1 - pEvil) * (1 - pEvil);
        double expectedSameRole = roleCounts.values().stream()
                .mapToDouble(c -> {
                    double p = c / (double) seats;
                    return p * p;
                }).sum();

        int pairs = 0, sameRole = 0, sameTeam = 0;
        int longestRoleStreak = 1, longestTeamStreak = 1;
        Map<String, Integer> roleStreak = new java.util.HashMap<>();
        Map<String, Integer> teamStreak = new java.util.HashMap<>();
        int backToBackEvil = 0, evilFollowups = 0;

        for (int i = 1; i < history.size(); i++) {
            for (String name : names) {
                Role previous = history.get(i - 1).get(name);
                Role current = history.get(i).get(name);
                pairs++;

                if (previous == current) {
                    sameRole++;
                    longestRoleStreak = Math.max(longestRoleStreak,
                            roleStreak.merge(name, 1, Integer::sum) + 1);
                } else {
                    roleStreak.put(name, 0);
                }

                if (previous.team() == current.team()) {
                    sameTeam++;
                    longestTeamStreak = Math.max(longestTeamStreak,
                            teamStreak.merge(name, 1, Integer::sum) + 1);
                } else {
                    teamStreak.put(name, 0);
                }

                if (previous.isEvil()) {
                    evilFollowups++;
                    if (current.isEvil()) backToBackEvil++;
                }
            }
        }

        double observedSameRole = sameRole / (double) pairs;
        double observedSameTeam = sameTeam / (double) pairs;
        double observedEvilAgain = backToBackEvil / (double) evilFollowups;

        System.out.printf("%n==== independence of consecutive deals ====%n");
        System.out.printf("%d games x %d players, %d consecutive pairs%n%n", GAMES, PLAYERS, pairs);
        System.out.printf("%-34s %-10s %-10s%n", "", "OBSERVED", "CHANCE");
        System.out.printf("%-34s %-10.4f %-10.4f%n", "same exact role again", observedSameRole, expectedSameRole);
        System.out.printf("%-34s %-10.4f %-10.4f%n", "same alignment again", observedSameTeam, expectedSameTeam);
        System.out.printf("%-34s %-10.4f %-10.4f%n", "Evil again, given Evil", observedEvilAgain, pEvil);
        System.out.printf("%n%-34s %d%n", "times a player repeated a role", sameRole);
        System.out.printf("%-34s %d%n", "longest same-role streak", longestRoleStreak);
        System.out.printf("%-34s %d%n", "longest same-alignment streak", longestTeamStreak);
        System.out.printf("%-34s %.4f%n%n", "P(Evil) per seat", pEvil);

        // Independence in both directions: too few repeats leaks as badly as too many.
        assertThat(observedSameRole).isCloseTo(expectedSameRole, within(0.015));
        assertThat(observedSameTeam).isCloseTo(expectedSameTeam, within(0.02));
        assertThat(observedEvilAgain).isCloseTo(pEvil, within(0.03));
        assertThat(sameRole).as("repeats must actually happen").isPositive();
        assertThat(longestTeamStreak).as("streaks of 3+ are expected at this sample size")
                .isGreaterThanOrEqualTo(3);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    @Test
    @DisplayName("1000 games per count: every seat gets every role at the rate chance predicts")
    void noSeatOrRoleBias() {
        int gamesPerCount = 1_000;
        System.out.printf("%n==== per-seat fairness, %d games at each count ====%n", gamesPerCount);
        System.out.printf("%-8s %-7s %-22s %-22s%n",
                "PLAYERS", "GAMES", "EVIL RATE min..max", "WORST ROLE DEVIATION");

        for (int players = RoleTable.MIN_PLAYERS; players <= RoleTable.MAX_PLAYERS; players++) {
            Dealer dealer = new Dealer();
            List<String> names = IntStream.range(0, players).mapToObj(i -> "P" + i).toList();

            Map<String, Integer> evilBySeat = new java.util.TreeMap<>();
            Map<String, Map<Role, Integer>> roleBySeat = new java.util.TreeMap<>();
            names.forEach(n -> roleBySeat.put(n, new EnumMap<>(Role.class)));

            for (int i = 0; i < gamesPerCount; i++) {
                for (Assignment a : dealer.deal(names).assignments()) {
                    roleBySeat.get(a.playerName()).merge(a.role(), 1, Integer::sum);
                    if (a.role().isEvil()) evilBySeat.merge(a.playerName(), 1, Integer::sum);
                }
            }

            double expectedEvil = RoleTable.forPlayers(players).evilCount() / (double) players;
            double minEvil = names.stream()
                    .mapToDouble(n -> evilBySeat.getOrDefault(n, 0) / (double) gamesPerCount).min().orElseThrow();
            double maxEvil = names.stream()
                    .mapToDouble(n -> evilBySeat.getOrDefault(n, 0) / (double) gamesPerCount).max().orElseThrow();

            // Chi-square goodness of fit per role: is this role spread evenly across seats?
            // A raw "worst percentage deviation" bound would be arbitrary and flaky, because
            // rare roles have small per-seat counts and so large relative swings by nature.
            double worstChiSquare = 0;
            String worstRole = "-";
            for (Role role : Role.values()) {
                int total = roleBySeat.values().stream().mapToInt(m -> m.getOrDefault(role, 0)).sum();
                if (total < 100) continue;                  // too few to say anything
                double expected = total / (double) players;
                double chiSquare = names.stream()
                        .mapToDouble(n -> roleBySeat.get(n).getOrDefault(role, 0) - expected)
                        .map(diff -> diff * diff / expected)
                        .sum();
                if (chiSquare > worstChiSquare) {
                    worstChiSquare = chiSquare;
                    worstRole = role.displayName();
                }
            }

            // df = players - 1, so the p=0.001 critical value is around 26-28. The bound below is
            // set well above that: this runs on every build, and a genuine positional bias — a
            // hash-ordered collection, an off-by-one in the shuffle — produces values in the
            // hundreds, so the extra headroom costs no real sensitivity and buys stability.
            System.out.printf("%-8d %-7d %-22s %-22s%n", players, gamesPerCount,
                    "%.3f..%.3f (exp %.3f)".formatted(minEvil, maxEvil, expectedEvil),
                    "%s x2=%.1f".formatted(worstRole, worstChiSquare));

            assertThat(minEvil).as("lowest Evil rate at %d players", players)
                    .isCloseTo(expectedEvil, within(0.05));
            assertThat(maxEvil).as("highest Evil rate at %d players", players)
                    .isCloseTo(expectedEvil, within(0.05));
            assertThat(worstChiSquare)
                    .as("worst per-seat role distribution at %d players (%s)", players, worstRole)
                    .isLessThan(45.0);
        }
        System.out.println();
    }

    @Test
    @DisplayName("nothing is cached — 1000 identical requests give 1000 different games")
    void nothingIsCached() {
        List<String> names = IntStream.range(0, PLAYERS).mapToObj(i -> "P" + i).toList();
        int runs = 1_000;

        // Same dealer, same input, repeatedly.
        Dealer dealer = new Dealer();
        java.util.Set<String> fromOneDealer = new java.util.HashSet<>();
        for (int i = 0; i < runs; i++) {
            fromOneDealer.add(fingerprint(dealer.deal(names)));
        }

        // A fresh dealer per call, in case state were being reused across instances.
        java.util.Set<String> fromFreshDealers = new java.util.HashSet<>();
        for (int i = 0; i < runs; i++) {
            fromFreshDealers.add(fingerprint(new Dealer().deal(names)));
        }

        System.out.printf("%n==== caching ====%n");
        System.out.printf("  %d deals, one dealer     -> %d distinct outcomes%n", runs, fromOneDealer.size());
        System.out.printf("  %d deals, fresh dealers  -> %d distinct outcomes%n%n", runs, fromFreshDealers.size());

        // A handful of natural collisions is fine; anything cached would collapse to a few.
        assertThat(fromOneDealer).hasSizeGreaterThan(runs - 10);
        assertThat(fromFreshDealers).hasSizeGreaterThan(runs - 10);
    }

    /**
     * Java's String.hashCode collides for "Aa"/"BB" and every same-length combination of them.
     * If any part of dealing walked a HashMap or HashSet of player names, colliding names would
     * land in the same bucket and could be treated differently from the rest.
     */
    @Test
    @DisplayName("hash-colliding player names are dealt no differently")
    void hashCollidingNamesAreNotBiased() {
        List<String> colliding = List.of(
                "Aa", "BB", "AaAa", "AaBB", "BBAa", "BBBB", "AaAaAa", "AaAaBB", "AaBBAa", "AaBBBB");
        assertThat(colliding.stream().map(String::hashCode).distinct().count())
                .as("these names really do collide in pairs").isLessThan(colliding.size());

        Dealer dealer = new Dealer();
        int games = 1_000;
        Map<String, Integer> evil = new java.util.LinkedHashMap<>();
        for (int i = 0; i < games; i++) {
            dealer.deal(colliding).assignments().stream()
                    .filter(a -> a.role().isEvil())
                    .forEach(a -> evil.merge(a.playerName(), 1, Integer::sum));
        }

        System.out.printf("%n==== hash-colliding names, %d games ====%n", games);
        colliding.forEach(n -> System.out.printf("  %-8s hash %-12d Evil %5.1f%%%n",
                n, n.hashCode(), 100.0 * evil.getOrDefault(n, 0) / games));
        System.out.println();

        assertThat(evil).allSatisfy((name, count) ->
                assertThat(count / (double) games).as("Evil rate for %s", name)
                        .isBetween(0.35, 0.45));
    }

    @Test
    @DisplayName("the order names arrive in does not affect the outcome distribution")
    void inputOrderDoesNotMatter() {
        List<String> names = IntStream.range(0, PLAYERS).mapToObj(i -> "P" + i).toList();
        List<String> reversed = new ArrayList<>(names).reversed();
        int games = 2_000;

        Map<String, Integer> forward = evilRates(names, games);
        Map<String, Integer> backward = evilRates(reversed, games);

        System.out.printf("%n==== input order ====%n");
        System.out.printf("  %-6s %-12s %-12s%n", "SEAT", "AS GIVEN", "REVERSED");
        for (String name : names) {
            System.out.printf("  %-6s %-12.3f %-12.3f%n", name,
                    forward.getOrDefault(name, 0) / (double) games,
                    backward.getOrDefault(name, 0) / (double) games);
        }
        System.out.println();

        for (String name : names) {
            double a = forward.getOrDefault(name, 0) / (double) games;
            double b = backward.getOrDefault(name, 0) / (double) games;
            assertThat(a).as("Evil rate for %s is order-independent", name).isCloseTo(b, within(0.05));
        }
    }

    private Map<String, Integer> evilRates(List<String> names, int games) {
        Dealer dealer = new Dealer();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < games; i++) {
            dealer.deal(names).assignments().stream()
                    .filter(a -> a.role().isEvil())
                    .forEach(a -> counts.merge(a.playerName(), 1, Integer::sum));
        }
        return counts;
    }

    private String fingerprint(Deal deal) {
        return deal.assignments().stream()
                .map(a -> a.playerName() + ":" + a.role())
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("every seat is equally likely to be Evil — no positional bias")
    void noPositionalBias() {
        Dealer dealer = new Dealer();
        List<String> names = IntStream.range(0, PLAYERS).mapToObj(i -> "P" + i).toList();
        Map<String, Integer> evilCount = new java.util.TreeMap<>();

        int games = 5_000;
        for (int i = 0; i < games; i++) {
            dealer.deal(names).assignments().stream()
                    .filter(a -> a.role().isEvil())
                    .forEach(a -> evilCount.merge(a.playerName(), 1, Integer::sum));
        }

        System.out.printf("%n==== positional bias, %d games ====%n", games);
        evilCount.forEach((name, count) ->
                System.out.printf("  %-4s Evil %5.2f%%%n", name, 100.0 * count / games));
        System.out.println();

        // 4 Evil of 10 seats => 40% each.
        assertThat(evilCount).allSatisfy((name, count) ->
                assertThat(count / (double) games).as("Evil rate for %s", name)
                        .isBetween(0.37, 0.43));
    }
}
