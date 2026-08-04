package com.thavalon.domain;

import java.util.List;
import java.util.Map;

import static com.thavalon.domain.Role.*;

/**
 * Which roles are eligible, and how many Evil players there are, at each supported player count.
 *
 * <p>This is a straight transcription of the reference implementation's branching logic
 * (thavalon.py:91-119) into declared data. The numbers are deliberately identical to the
 * original, <em>including</em> the two the project README flags as unbalanced:
 *
 * <ul>
 *   <li><b>6 players</b> get 2 Evil — a 33% Evil share, the thinnest in the table.</li>
 *   <li><b>9 players</b> get 4 Evil, where standard Avalon gives 3.</li>
 * </ul>
 *
 * <p>The one intentional departure from the original table is that Nimue is not in the 5-player
 * Good pool — this group does not play her, so she is gone from {@link Role} entirely. That
 * leaves 5 players drawing 3 Good from the always-eligible core five, exactly as 6 players
 * draw 4 from it.
 *
 * <p>Both unbalanced counts are preserved on purpose so behaviour matches the original. Because
 * they live here as data rather than as control flow, changing either is a one-line edit that
 * {@code RoleTableTest} will still validate.
 */
public final class RoleTable {

    public static final int MIN_PLAYERS = 5;
    public static final int MAX_PLAYERS = 10;

    /** Always eligible, at every player count. */
    private static final List<Role> CORE_GOOD = List.of(MERLIN, PERCIVAL, TRISTAN, ISEULT, LANCELOT);

    /** Always eligible, at every player count. */
    private static final List<Role> CORE_EVIL = List.of(MORDRED, MORGANA, MAELAGANT);

    /** Eligible Good roles at 7+ players: the core five plus Arthur and Titania. */
    private static final List<Role> GOOD_7_PLUS = concat(CORE_GOOD, ARTHUR, TITANIA);

    public record Config(int players, int evilCount, List<Role> goodPool, List<Role> evilPool) {
        public int goodCount() {
            return players - evilCount;
        }
    }

    private static final Map<Integer, Config> TABLE = Map.of(
            //         players  evil  good pool                          evil pool
            5,  new Config(5,  2, CORE_GOOD,                         CORE_EVIL),
            6,  new Config(6,  2, CORE_GOOD,                         CORE_EVIL),
            7,  new Config(7,  3, GOOD_7_PLUS,                       CORE_EVIL),
            8,  new Config(8,  3, GOOD_7_PLUS,                       concat(CORE_EVIL, AGRAVAINE)),
            9,  new Config(9,  4, GOOD_7_PLUS,                       concat(CORE_EVIL, AGRAVAINE)),
            10, new Config(10, 4, GOOD_7_PLUS,                       concat(CORE_EVIL, AGRAVAINE, COLGREVANCE))
    );

    private RoleTable() {
    }

    public static Config forPlayers(int players) {
        Config config = TABLE.get(players);
        if (config == null) {
            throw new IllegalArgumentException(
                    "THavalon supports " + MIN_PLAYERS + " to " + MAX_PLAYERS + " players, got " + players);
        }
        return config;
    }

    public static boolean isSupported(int players) {
        return TABLE.containsKey(players);
    }

    private static List<Role> concat(List<Role> base, Role... extra) {
        return java.util.stream.Stream.concat(base.stream(), java.util.Arrays.stream(extra)).toList();
    }
}
