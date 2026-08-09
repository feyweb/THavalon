package com.thavalon.domain;

import java.util.List;
import java.util.Optional;

/**
 * The result of dealing one game.
 *
 * @param firstProposer who proposes the first mission, drawn at random as the reference
 *                      implementation does (thavalon.py:206-209). Unlike a role this is public:
 *                      every player is shown the same name.
 */
public record Deal(List<Assignment> assignments, String firstProposer) {

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
