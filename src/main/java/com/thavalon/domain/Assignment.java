package com.thavalon.domain;

import java.util.List;

/** A fully resolved role assignment: who the player is, what they are, and what they know. */
public record Assignment(String playerName, Role role, boolean assassin, List<String> info) {

    public Assignment {
        info = List.copyOf(info);
    }

    public Team team() {
        return role.team();
    }
}
