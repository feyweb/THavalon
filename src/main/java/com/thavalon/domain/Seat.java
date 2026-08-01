package com.thavalon.domain;

/** A player and the role they were dealt, before information lines are generated. */
public record Seat(String playerName, Role role) {

    public Team team() {
        return role.team();
    }

    public boolean isEvil() {
        return role.isEvil();
    }
}
