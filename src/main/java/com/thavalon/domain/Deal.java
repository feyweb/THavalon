package com.thavalon.domain;

import java.util.List;
import java.util.Optional;

/**
 * The result of dealing one game.
 *
 * <p>The reference implementation also nominated a first proposer (thavalon.py:206-209). That is
 * deliberately absent here — the table picks who proposes first by its own means.
 */
public record Deal(List<Assignment> assignments) {

    public Deal {
        assignments = List.copyOf(assignments);
    }

    public Optional<Assignment> forPlayer(String playerName) {
        return assignments.stream().filter(a -> a.playerName().equals(playerName)).findFirst();
    }

    public List<Role> rolesInGame() {
        return assignments.stream().map(Assignment::role).toList();
    }
}
