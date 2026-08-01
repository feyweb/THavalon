package com.thavalon.game;

import org.springframework.http.HttpStatus;

/** A client-visible failure. Carries the status and a stable machine-readable code. */
public class GameException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GameException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static GameException notFound(String gameId) {
        return new GameException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
                "No game with ID " + gameId + ". Check the game ID, or it may have expired.");
    }

    public static GameException badToken() {
        return new GameException(HttpStatus.UNAUTHORIZED, "BAD_TOKEN",
                "You are not a player in this game on this device.");
    }

    public static GameException notHost() {
        return new GameException(HttpStatus.FORBIDDEN, "NOT_HOST",
                "Only the player who created the game can do that.");
    }

    public static GameException duplicateName(String name) {
        return new GameException(HttpStatus.CONFLICT, "DUPLICATE_NAME",
                "Someone in this game is already called " + name + ". Pick another name.");
    }

    public static GameException gameFull(int max) {
        return new GameException(HttpStatus.LOCKED, "GAME_FULL",
                "This game already has " + max + " players, which is the maximum.");
    }

    public static GameException alreadyStarted() {
        return new GameException(HttpStatus.GONE, "ALREADY_STARTED",
                "This game has already started, so nobody else can join.");
    }

    public static GameException notEnoughPlayers(int current, int min) {
        return new GameException(HttpStatus.CONFLICT, "NOT_ENOUGH_PLAYERS",
                "THavalon needs at least " + min + " players. You have " + current + ".");
    }

    public static GameException auditSealed(java.time.Instant opensAt) {
        return new GameException(HttpStatus.LOCKED, "AUDIT_SEALED",
                "This game's audit is sealed until " + opensAt
                        + ". It opens once the game is over, so it cannot be read mid-game.");
    }

    public static GameException invalidName() {
        return new GameException(HttpStatus.BAD_REQUEST, "INVALID_NAME",
                "Name must be 1 to 20 characters.");
    }

    public static GameException invalidGameId() {
        return new GameException(HttpStatus.BAD_REQUEST, "INVALID_GAME_ID",
                "Game ID must be 3 to 12 letters, digits or hyphens, starting with a letter or digit.");
    }

    public static GameException gameIdTaken(String gameId) {
        return new GameException(HttpStatus.CONFLICT, "GAME_ID_TAKEN",
                "A game called " + gameId + " is already running. Pick another ID.");
    }
}
