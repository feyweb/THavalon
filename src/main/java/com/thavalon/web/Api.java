package com.thavalon.web;

import com.thavalon.domain.RoleTable;
import com.thavalon.game.Game;
import com.thavalon.game.GameState;
import com.thavalon.game.Player;

import java.util.List;

/**
 * Request and response shapes.
 *
 * <p>Deliberately separate from {@link Game} and {@link Player}: those carry player tokens, which
 * must never leak into a response describing anyone other than the caller.
 */
public final class Api {

    private Api() {
    }

    public record NameRequest(String name) {
    }

    /** @param gameId an ID chosen by the host, or blank/absent for a generated one */
    public record CreateRequest(String name, String gameId) {
    }

    public record JoinedResponse(String gameId, String playerToken, String name, boolean host) {
    }

    public record ErrorResponse(String code, String message) {
    }

    /** Polled by the lobby. Contains no secrets, so anyone holding the game code may read it. */
    public record LobbyResponse(
            String gameId,
            String state,
            List<String> players,
            String hostName,
            int minPlayers,
            int maxPlayers,
            boolean canStart,
            boolean youAreHost,
            /** The caller's own name, so the lobby can mark which player they are. */
            String yourName) {

        public static LobbyResponse of(Game game, String token) {
            int count = game.getPlayers().size();
            return new LobbyResponse(
                    game.getId(),
                    game.getState().name(),
                    game.getPlayers().stream().map(Player::getName).toList(),
                    game.host().map(Player::getName).orElse(null),
                    RoleTable.MIN_PLAYERS,
                    RoleTable.MAX_PLAYERS,
                    game.getState() == GameState.LOBBY && count >= RoleTable.MIN_PLAYERS,
                    game.isHost(token),
                    game.playerByToken(token).map(Player::getName).orElse(null));
        }
    }

    /** The caller's own card. Served only against their private token. */
    public record MeResponse(
            String gameId,
            String state,
            String name,
            String role,
            String team,
            String description,
            List<String> info,
            boolean assassin,
            boolean host) {

        public static MeResponse of(Game game, Player me) {
            return new MeResponse(
                    game.getId(),
                    game.getState().name(),
                    me.getName(),
                    me.getRole() == null ? null : me.getRole().displayName(),
                    me.getRole() == null ? null : me.getRole().team().name(),
                    me.getRole() == null ? null : me.getRole().description(),
                    me.getInfo(),
                    me.isAssassin(),
                    game.isHost(me.getToken()));
        }
    }

}
