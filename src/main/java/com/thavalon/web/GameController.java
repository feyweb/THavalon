package com.thavalon.web;

import com.thavalon.game.Game;
import com.thavalon.game.GameService;
import com.thavalon.game.Player;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class GameController {

    /** The caller's private credential, held in the browser's localStorage. */
    public static final String TOKEN_HEADER = "X-Player-Token";

    private final GameService games;

    public GameController(GameService games) {
        this.games = games;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/games")
    @ResponseStatus(HttpStatus.CREATED)
    public Api.JoinedResponse create(@RequestBody Api.CreateRequest request) {
        GameService.Joined joined = games.createGame(request.name(), request.gameId());
        return new Api.JoinedResponse(joined.gameId(), joined.playerToken(), joined.name(), joined.host());
    }

    @PostMapping("/games/{gameId}/players")
    @ResponseStatus(HttpStatus.CREATED)
    public Api.JoinedResponse join(@PathVariable String gameId, @RequestBody Api.NameRequest request) {
        GameService.Joined joined = games.join(gameId, request.name());
        return new Api.JoinedResponse(joined.gameId(), joined.playerToken(), joined.name(), joined.host());
    }

    @DeleteMapping("/games/{gameId}/players/me")
    public ResponseEntity<Void> leave(
            @PathVariable String gameId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        games.leave(gameId, token);
        return ResponseEntity.noContent().build();
    }

    /** Lobby state. Polled every couple of seconds while players are joining. */
    @GetMapping("/games/{gameId}")
    public Api.LobbyResponse lobby(
            @PathVariable String gameId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return Api.LobbyResponse.of(games.get(gameId), token);
    }

    @PostMapping("/games/{gameId}/start")
    public Api.LobbyResponse start(
            @PathVariable String gameId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return Api.LobbyResponse.of(games.start(gameId, token), token);
    }

    /** The caller's own role card. */
    @GetMapping("/games/{gameId}/me")
    public Api.MeResponse me(
            @PathVariable String gameId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        Game game = games.get(gameId);
        Player me = games.me(gameId, token);
        return Api.MeResponse.of(game, me);
    }

}
