package com.thavalon.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deals one THavalon game: picks the roles in play, assigns them to players, chooses the
 * Assassin, and generates each player's information.
 *
 * <p>Instances are cheap; a {@link SecureRandom} is used by default so no two games share a
 * seed. Tests may inject a seeded {@link Random} for reproducibility.
 */
public final class Dealer {

    private final Random random;

    public Dealer() {
        this(new SecureRandom());
    }

    public Dealer(Random random) {
        this.random = random;
    }

    public Deal deal(List<String> playerNames) {
        List<String> names = validated(playerNames);
        RoleTable.Config config = RoleTable.forPlayers(names.size());

        List<Role> goodRoles = withLoversRepaired(sample(config.goodPool(), config.goodCount()), config);
        List<Role> evilRoles = sample(config.evilPool(), config.evilCount());

        List<Seat> seats = assignSeats(names, goodRoles, evilRoles);
        Seat assassin = pickAssassin(seats);

        List<Assignment> assignments = seats.stream()
                .map(seat -> new Assignment(
                        seat.playerName(),
                        seat.role(),
                        seat.equals(assassin),
                        informationFor(seat, seats, seat.equals(assassin))))
                .toList();

        return new Deal(assignments);
    }

    /**
     * Tristan and Iseult must appear together or not at all. If exactly one was drawn, the
     * original either swaps the lone lover out for an unused role or upgrades to the pair,
     * at even odds (thavalon.py:135-150).
     *
     * <p>The original crashes when the replacement pool is empty, which happens at 6 and 10
     * players — the pool is sized {@code goodPool - goodCount - 1}, which is zero at both.
     * Here an empty pool simply falls through to the upgrade branch, which is always viable
     * with two or more Good players. The even odds are preserved wherever the pool allows.
     */
    private List<Role> withLoversRepaired(List<Role> dealt, RoleTable.Config config) {
        if (dealt.stream().filter(Role::isLover).count() != 1 || config.goodCount() < 2) {
            return dealt;
        }

        List<Role> roles = new ArrayList<>(dealt);
        roles.removeIf(Role::isLover);

        List<Role> replacements = config.goodPool().stream()
                .filter(role -> !role.isLover() && !roles.contains(role))
                .toList();

        if (!replacements.isEmpty() && random.nextBoolean()) {
            roles.add(replacements.get(random.nextInt(replacements.size())));
        } else {
            roles.remove(random.nextInt(roles.size()));
            roles.add(Role.TRISTAN);
            roles.add(Role.ISEULT);
        }
        return roles;
    }

    private List<Seat> assignSeats(List<String> names, List<Role> goodRoles, List<Role> evilRoles) {
        List<String> shuffled = new ArrayList<>(names);
        Collections.shuffle(shuffled, random);

        List<Role> roles = new ArrayList<>(goodRoles);
        roles.addAll(evilRoles);
        if (roles.size() != shuffled.size()) {
            throw new IllegalStateException(
                    "Dealt %d roles for %d players".formatted(roles.size(), shuffled.size()));
        }

        List<Seat> seats = new ArrayList<>(roles.size());
        for (int i = 0; i < roles.size(); i++) {
            seats.add(new Seat(shuffled.get(i), roles.get(i)));
        }
        return List.copyOf(seats);
    }

    /**
     * Any Evil player may hold the kill, Colgrevance included.
     *
     * <p>This matches the reference implementation (thavalon.py:170-178), which shuffles the
     * players and takes the first Evil seat — a uniform choice among Evil. Picking here, after
     * roles exist, is uniform over the same set and so gives the identical distribution.
     *
     * <p>Colgrevance holding the kill costs Evil nothing: by the time an assassination happens
     * Good already has three successful missions, so there is no secrecy left to protect and she
     * can simply claim the shot.
     */
    private Seat pickAssassin(List<Seat> seats) {
        List<Seat> evil = seats.stream().filter(Seat::isEvil).toList();
        if (evil.isEmpty()) {
            throw new IllegalStateException("No Evil players to assassinate with");
        }
        return evil.get(random.nextInt(evil.size()));
    }

    private List<String> informationFor(Seat seat, List<Seat> seats, boolean isAssassin) {
        List<String> info = new ArrayList<>(RoleInfo.baseInfo(seat, seats));
        Collections.shuffle(info, random);
        info.addAll(RoleInfo.notices(seat, seats, isAssassin));
        return info;
    }

    private <T> List<T> sample(List<T> pool, int count) {
        if (count > pool.size()) {
            throw new IllegalStateException(
                    "Need %d roles but only %d are eligible".formatted(count, pool.size()));
        }
        List<T> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        return new ArrayList<>(shuffled.subList(0, count));
    }

    private List<String> validated(List<String> playerNames) {
        if (playerNames == null || playerNames.isEmpty()) {
            throw new IllegalArgumentException("No players supplied");
        }
        List<String> names = playerNames.stream().map(n -> n == null ? "" : n.trim()).toList();
        if (names.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Player names cannot be blank");
        }
        Set<String> distinct = new LinkedHashSet<>(
                names.stream().map(n -> n.toLowerCase(java.util.Locale.ROOT)).toList());
        if (distinct.size() != names.size()) {
            throw new IllegalArgumentException("Player names must be unique");
        }
        if (!RoleTable.isSupported(names.size())) {
            throw new IllegalArgumentException("THavalon supports %d to %d players, got %d"
                    .formatted(RoleTable.MIN_PLAYERS, RoleTable.MAX_PLAYERS, names.size()));
        }
        return names;
    }
}
