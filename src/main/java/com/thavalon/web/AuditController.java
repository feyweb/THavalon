package com.thavalon.web;

import com.thavalon.audit.AuditLog;
import com.thavalon.game.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only access to finished games: the code, when it started, and who was dealt what.
 *
 * <p>There is no password. A game's trail is sealed until it is definitively over — see
 * {@link GameService#auditFor(String)} — so there is nothing here that could be read during play,
 * and therefore nothing worth guarding with a secret that players could work out.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final GameService games;

    public AuditController(GameService games) {
        this.games = games;
    }

    /** Games whose audit has opened, newest first. */
    @GetMapping
    public List<AuditLog.Summary> index() {
        return games.auditIndex();
    }

    /** One game's trail. 423 while the game is still sealed. */
    @GetMapping("/{gameId}")
    public GameService.AuditView game(@PathVariable String gameId) {
        return games.auditFor(gameId);
    }
}
