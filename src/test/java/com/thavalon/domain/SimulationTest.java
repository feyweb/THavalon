package com.thavalon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deals a spread of complete games and checks every structural and information rule against the
 * reference implementation's behaviour, independently of how {@link Dealer} and {@link RoleInfo}
 * compute them.
 *
 * <p>The point is to re-derive what each player should see from the dealt roles alone, then
 * compare. A bug shared between the code and its test would have to be made twice, in two
 * different shapes, to slip through.
 */
class SimulationTest {

    private static final int GAMES = 10_000;

    private static final Set<String> NOTICES =
            Set.of(RoleInfo.COLGREVANCE_NOTICE, RoleInfo.TITANIA_NOTICE, RoleInfo.ASSASSIN_NOTICE);

    private record Game(int players, Deal deal, Map<String, Role> roles) {
        Set<Role> rolesInPlay() {
            return new HashSet<>(roles.values());
        }

        String holderOf(Role role) {
            return roles.entrySet().stream()
                    .filter(e -> e.getValue() == role).map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        }
    }

    @Test
    @DisplayName("10,000 games across every player count satisfy every rule")
    void simulate() {
        Dealer dealer = new Dealer();          // SecureRandom: a different spread every run
        List<String> violations = new ArrayList<>();
        Map<Integer, Integer> gamesPerCount = new TreeMap<>();
        Map<Role, Integer> roleFrequency = new EnumMap<>(Role.class);
        int loverPairs = 0;
        int checks = 0;

        for (int i = 0; i < GAMES; i++) {
            int players = RoleTable.MIN_PLAYERS + (i % 6);          // cycle 5..10
            List<String> names = IntStream.range(0, players).mapToObj(n -> "P" + n).toList();

            Deal deal = dealer.deal(names);
            Map<String, Role> roles = deal.assignments().stream().collect(Collectors.toMap(
                    Assignment::playerName, Assignment::role, (a, b) -> a, LinkedHashMap::new));
            Game game = new Game(players, deal, roles);

            gamesPerCount.merge(players, 1, Integer::sum);
            roles.values().forEach(r -> roleFrequency.merge(r, 1, Integer::sum));
            if (roles.containsValue(Role.TRISTAN)) loverPairs++;

            checks += check(game, i, violations);
        }

        report(gamesPerCount, roleFrequency, loverPairs, checks, violations);
        assertThat(violations).as("rule violations").isEmpty();
    }

    /** Returns the number of assertions made, so the report can state how much was checked. */
    private int check(Game game, int index, List<String> violations) {
        int n = 0;
        RoleTable.Config config = RoleTable.forPlayers(game.players());
        Map<String, Role> roles = game.roles();

        // ---- structure ----
        n += expect(violations, index, "every player dealt exactly one role",
                roles.size() == game.players());
        n += expect(violations, index, "roles are distinct",
                game.rolesInPlay().size() == game.players());
        n += expect(violations, index, "Evil count matches the table",
                roles.values().stream().filter(Role::isEvil).count() == config.evilCount());
        n += expect(violations, index, "Good count matches the table",
                roles.values().stream().filter(Role::isGood).count() == config.goodCount());
        n += expect(violations, index, "only roles eligible at this count are dealt",
                roles.values().stream().allMatch(r ->
                        config.goodPool().contains(r) || config.evilPool().contains(r)));

        long lovers = roles.values().stream().filter(Role::isLover).count();
        n += expect(violations, index, "lovers appear as a pair or not at all",
                lovers == 0 || lovers == 2);
        if (game.players() == 6 || game.players() == 10) {
            n += expect(violations, index, "lovers are unavoidable at 6 and 10 players", lovers == 2);
        }

        // Count-gated roles.
        n += expect(violations, index, "Nimue only at 5 players",
                !roles.containsValue(Role.NIMUE) || game.players() == 5);
        n += expect(violations, index, "Arthur only at 7+",
                !roles.containsValue(Role.ARTHUR) || game.players() >= 7);
        n += expect(violations, index, "Titania only at 7+",
                !roles.containsValue(Role.TITANIA) || game.players() >= 7);
        n += expect(violations, index, "Agravaine only at 8+",
                !roles.containsValue(Role.AGRAVAINE) || game.players() >= 8);
        n += expect(violations, index, "Colgrevance only at 10",
                !roles.containsValue(Role.COLGREVANCE) || game.players() == 10);

        List<Assignment> assassins = game.deal().assignments().stream()
                .filter(Assignment::assassin).toList();
        n += expect(violations, index, "exactly one Assassin", assassins.size() == 1);
        if (assassins.size() == 1) {
            n += expect(violations, index, "Assassin is Evil", assassins.getFirst().role().isEvil());
        }

        // ---- information, re-derived from the dealt roles ----
        for (Assignment me : game.deal().assignments()) {
            Set<String> facts = me.info().stream()
                    .filter(line -> !NOTICES.contains(line))
                    .collect(Collectors.toSet());
            n += expect(violations, index, me.role() + " sees exactly what the rules allow",
                    facts.equals(expectedFacts(me, game)));

            boolean evilNotColgrevance = me.role().isEvil() && me.role() != Role.COLGREVANCE;
            n += expect(violations, index, me.role() + " Colgrevance notice",
                    me.info().contains(RoleInfo.COLGREVANCE_NOTICE)
                            == (evilNotColgrevance && roles.containsValue(Role.COLGREVANCE)));
            n += expect(violations, index, me.role() + " Titania notice",
                    me.info().contains(RoleInfo.TITANIA_NOTICE)
                            == (evilNotColgrevance && roles.containsValue(Role.TITANIA)));
            n += expect(violations, index, me.role() + " Assassin notice matches flag",
                    me.info().contains(RoleInfo.ASSASSIN_NOTICE) == me.assassin());
        }
        return n;
    }

    /** What this player must see, derived from the dealt roles without consulting RoleInfo. */
    private Set<String> expectedFacts(Assignment me, Game game) {
        Map<String, Role> roles = game.roles();
        Set<String> expected = new HashSet<>();

        switch (me.role()) {
            case MERLIN -> roles.forEach((name, role) -> {
                if ((role.isEvil() && role != Role.MORDRED) || role == Role.LANCELOT) {
                    expected.add(name + " is Evil.");
                }
            });
            case PERCIVAL -> roles.forEach((name, role) -> {
                if (role == Role.MERLIN || role == Role.MORGANA) {
                    expected.add(name + " is Merlin or Morgana.");
                }
            });
            case TRISTAN -> {
                String other = game.holderOf(Role.ISEULT);
                if (other != null) expected.add(other + " is Iseult.");
            }
            case ISEULT -> {
                String other = game.holderOf(Role.TRISTAN);
                if (other != null) expected.add(other + " is Tristan.");
            }
            case ARTHUR -> roles.values().stream()
                    .filter(r -> r.isGood() && r != Role.ARTHUR)
                    .forEach(r -> expected.add(r.displayName()));
            case NIMUE -> roles.values().stream()
                    .filter(r -> r != Role.NIMUE)
                    .forEach(r -> expected.add(r.displayName()));
            case LANCELOT, TITANIA -> {
                // Ability roles receive nothing.
            }
            case MORDRED, MORGANA, MAELAGANT, AGRAVAINE -> roles.forEach((name, role) -> {
                boolean otherEvilTheyCanSee =
                        role.isEvil() && role != Role.COLGREVANCE && !name.equals(me.playerName());
                if (otherEvilTheyCanSee || role == Role.TITANIA) {
                    expected.add(name + " is Evil.");
                }
            });
            case COLGREVANCE -> roles.forEach((name, role) -> {
                if (role.isEvil() && !name.equals(me.playerName())) {
                    expected.add(name + " is " + role.displayName());
                }
            });
        }
        return expected;
    }

    private int expect(List<String> violations, int game, String rule, boolean holds) {
        if (!holds) {
            violations.add("game " + game + ": " + rule);
        }
        return 1;
    }

    private void report(Map<Integer, Integer> gamesPerCount, Map<Role, Integer> roleFrequency,
                        int loverPairs, int checks, List<String> violations) {
        System.out.println("\n==== THavalon simulation: " + GAMES + " games ====\n");

        System.out.printf("%-9s %-7s %-6s %-6s%n", "PLAYERS", "GAMES", "EVIL", "GOOD");
        gamesPerCount.forEach((players, count) -> {
            RoleTable.Config c = RoleTable.forPlayers(players);
            System.out.printf("%-9d %-7d %-6d %-6d%n", players, count, c.evilCount(), c.goodCount());
        });

        System.out.println("\nrole appearances across all games");
        for (Role role : Role.values()) {
            int count = roleFrequency.getOrDefault(role, 0);
            System.out.printf("  %-12s %-5s %s%n", role.displayName(), count,
                    "#".repeat(Math.min(count, 60)));
        }

        System.out.println("\ngames containing the lover pair: " + loverPairs + "/" + GAMES);
        System.out.println("assertions made               : " + checks);
        System.out.println("violations                    : " + violations.size());
        violations.stream().limit(20).forEach(v -> System.out.println("  " + v));
        System.out.println();
    }
}
