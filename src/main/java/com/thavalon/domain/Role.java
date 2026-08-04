package com.thavalon.domain;

import static com.thavalon.domain.Team.EVIL;
import static com.thavalon.domain.Team.GOOD;

/**
 * The THavalon roles. Descriptions are ported word-for-word from {@code get_role_description}
 * in the reference implementation (aquadrizzt/THavalon, thavalon.py:8-24), with one exception:
 * Colgrevance's reads "possesses" where the original reads "possess". These strings are printed
 * on a player's role card, so the typo is not worth reproducing.
 *
 * <p>Nimue is deliberately absent: this group does not play her. She was the reference
 * implementation's only 5-player-exclusive role, so dropping her leaves the 5-player Good pool
 * equal to the always-eligible core five and changes nothing at 6 or more players.
 */
public enum Role {

    TRISTAN(GOOD, "Tristan", """
            The person you see is also Good and is aware that you are Good.
            You and Iseult are collectively a valid Assassination target."""),

    ISEULT(GOOD, "Iseult", """
            The person you see is also Good and is aware that you are Good.
            You and Tristan are collectively a valid Assassination target."""),

    MERLIN(GOOD, "Merlin", """
            You know which people have Evil roles, but not who has any specific role.
            You are a valid Assassination target."""),

    PERCIVAL(GOOD, "Percival",
            "You know which people have the Merlin or Morgana roles, but not specifically who has each."),

    LANCELOT(GOOD, "Lancelot", """
            You may play Reversal cards while on missions.
            You appear Evil to Merlin."""),

    ARTHUR(GOOD, "Arthur", """
            You know which Good roles are in the game, but not who has any given role.
            If two missions have Failed, and less than two missions have Succeeded, you may declare as Arthur.
            After declaring, your vote on team proposals is counted twice, but you are unable to be on mission teams until the 5th mission.
            After declaring, you are immune to any effect that can forcibly change your vote."""),

    TITANIA(GOOD, "Titania",
            "You appear as Evil to all players with Evil roles (except Colgrevance)."),

    MORDRED(EVIL, "Mordred", """
            You are hidden from all Good Information roles.
            Like other Evil characters, you know who else is Evil (except Colgrevance)."""),

    MORGANA(EVIL, "Morgana", """
            You appear like Merlin to Percival.
            Like other Evil characters, you know who else is Evil (except Colgrevance)."""),

    MAELAGANT(EVIL, "Maelagant", """
            You may play Reversal cards while on missions.
            Like other Evil characters, you know who else is Evil (except Colgrevance)."""),

    AGRAVAINE(EVIL, "Agravaine", """
            You must play Fail cards while on missions.
            If you are on a mission that Succeeds, you may declare as Agravaine to cause it to Fail instead.
            Like other Evil characters, you know who else is Evil (except Colgrevance)."""),

    COLGREVANCE(EVIL, "Colgrevance", """
            You know not only who else is Evil, but what role each other Evil player possesses.
            Evil players know that there is a Colgrevance, but do not know that it is you.""");

    private final Team team;
    private final String displayName;
    private final String description;

    Role(Team team, String displayName, String description) {
        this.team = team;
        this.displayName = displayName;
        this.description = description;
    }

    public Team team() {
        return team;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public boolean isGood() {
        return team == GOOD;
    }

    public boolean isEvil() {
        return team == EVIL;
    }

    /** Tristan and Iseult must always appear as a pair, or not at all. */
    public boolean isLover() {
        return this == TRISTAN || this == ISEULT;
    }
}
