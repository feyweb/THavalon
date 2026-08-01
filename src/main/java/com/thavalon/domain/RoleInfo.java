package com.thavalon.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the information lines each role receives.
 *
 * <p>Ported from {@code get_role_information} (thavalon.py:32-48) and the Evil post-pass
 * (thavalon.py:186-192). Two differences from the original:
 *
 * <ul>
 *   <li>Dispatches on the dealt role, rather than building all thirteen roles' lists per player
 *       and discarding twelve.</li>
 *   <li>Compares roles by enum identity. The original used Python's {@code is} operator on
 *       string literals, which works only because CPython interns them.</li>
 * </ul>
 */
public final class RoleInfo {

    public static final String COLGREVANCE_NOTICE =
            "Colgrevance lurks in the shadows. (There is another Evil that you do not see.)";
    public static final String TITANIA_NOTICE =
            "Titania has infiltrated your ranks. (One of the people you see is not Evil.)";
    public static final String ASSASSIN_NOTICE = "You are the Assassin.";

    private RoleInfo() {
    }

    /** The role's own information, before any Evil-team notices are appended. */
    public static List<String> baseInfo(Seat me, List<Seat> seats) {
        return switch (me.role()) {
            case TRISTAN -> namesOf(seats, s -> s.role() == Role.ISEULT, "%s is Iseult.");
            case ISEULT -> namesOf(seats, s -> s.role() == Role.TRISTAN, "%s is Tristan.");

            // Merlin sees all Evil except Mordred, and additionally sees Lancelot as Evil.
            case MERLIN -> namesOf(seats,
                    s -> (s.isEvil() && s.role() != Role.MORDRED) || s.role() == Role.LANCELOT,
                    "%s is Evil.");

            case PERCIVAL -> namesOf(seats,
                    s -> s.role() == Role.MERLIN || s.role() == Role.MORGANA,
                    "%s is Merlin or Morgana.");

            // Arthur learns which Good roles are present, not who holds them.
            case ARTHUR -> seats.stream()
                    .filter(s -> s.role().isGood() && s.role() != Role.ARTHUR)
                    .map(s -> s.role().displayName())
                    .toList();

            // Nimue learns every role in the game, Good and Evil, but not who holds them.
            case NIMUE -> seats.stream()
                    .filter(s -> s.role() != Role.NIMUE)
                    .map(s -> s.role().displayName())
                    .toList();

            case LANCELOT, TITANIA -> List.of();

            // Evil see each other, but not Colgrevance. Titania appears among them.
            case MORDRED, MORGANA, MAELAGANT, AGRAVAINE -> namesOf(seats,
                    s -> (s.isEvil() && !s.playerName().equals(me.playerName()) && s.role() != Role.COLGREVANCE)
                            || s.role() == Role.TITANIA,
                    "%s is Evil.");

            // Colgrevance sees every other Evil player's exact role, and is not fooled by Titania.
            case COLGREVANCE -> seats.stream()
                    .filter(s -> s.isEvil() && !s.playerName().equals(me.playerName()))
                    .map(s -> "%s is %s".formatted(s.playerName(), s.role().displayName()))
                    .toList();
        };
    }

    /**
     * Notices appended after the base info is shuffled, so they always read last.
     * Only Evil players receive any of these.
     */
    public static List<String> notices(Seat me, List<Seat> seats, boolean isAssassin) {
        List<String> notices = new ArrayList<>();
        if (me.isEvil() && me.role() != Role.COLGREVANCE) {
            if (containsRole(seats, Role.COLGREVANCE)) {
                notices.add(COLGREVANCE_NOTICE);
            }
            if (containsRole(seats, Role.TITANIA)) {
                notices.add(TITANIA_NOTICE);
            }
        }
        if (isAssassin) {
            notices.add(ASSASSIN_NOTICE);
        }
        return notices;
    }

    private static boolean containsRole(List<Seat> seats, Role role) {
        return seats.stream().anyMatch(s -> s.role() == role);
    }

    private static List<String> namesOf(
            List<Seat> seats, java.util.function.Predicate<Seat> match, String template) {
        return seats.stream().filter(match).map(s -> template.formatted(s.playerName())).toList();
    }
}
