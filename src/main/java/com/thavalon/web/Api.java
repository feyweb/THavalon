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
            String yourName,
            /** Who proposes the first mission. Public, so everyone sees the same name. */
            String firstProposer) {

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
                    game.playerByToken(token).map(Player::getName).orElse(null),
                    game.getFirstProposer());
        }
    }

    /**
     * A game's roster for other apps — a tracker, a scoreboard — readable from any origin by game
     * ID alone (#19). Names in join order and the first proposer: nothing the lobby does not
     * already show to anyone holding the ID.
     *
     * <p>A separate shape from {@link LobbyResponse} on purpose. The lobby's fields serve the lobby
     * page and may change with it; this one is a contract with code outside this repository.
     * Join order is not seating order — the server never learns where anyone sits.
     */
    public record PlayersResponse(String gameId, String state, List<String> players, String firstProposer) {

        public static PlayersResponse of(Game game) {
            return new PlayersResponse(
                    game.getId(),
                    game.getState().name(),
                    game.getPlayers().stream().map(Player::getName).toList(),
                    game.getFirstProposer());
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
            boolean host,
            /** Everyone in the game, in join order — names only, the same public roster the lobby shows. */
            List<String> players,
            /** Who proposes the first mission. Public, so it can be shown without revealing a role. */
            String firstProposer) {

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
                    game.isHost(me.getToken()),
                    game.getPlayers().stream().map(Player::getName).toList(),
                    game.getFirstProposer());
        }
    }

}
