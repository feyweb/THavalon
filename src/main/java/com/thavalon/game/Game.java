package com.thavalon.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One game session. Mutated only under {@link GameService}'s per-game lock, so the mutable
 * collections here are safe.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Game {

    private String id;
    /**
     * Filename stem for this game's audit trail. Distinct from {@link #id} because IDs are chosen
     * by the host and get reused — "FRIDAY-NIGHT" every week — and two different games must not
     * append to the same trail.
     */
    private String auditKey;
    private String hostToken;
    private GameState state = GameState.LOBBY;
    private List<Player> players = new ArrayList<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Game() {
    }

    public Game(String id, String hostToken) {
        this.id = id;
        this.hostToken = hostToken;
    }

    public Optional<Player> playerByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return players.stream().filter(p -> token.equals(p.getToken())).findFirst();
    }

    public boolean hasName(String name) {
        return players.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name));
    }

    public boolean isHost(String token) {
        return hostToken != null && hostToken.equals(token);
    }

    public Optional<Player> host() {
        return playerByToken(hostToken);
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuditKey() {
        return auditKey;
    }

    public void setAuditKey(String auditKey) {
        this.auditKey = auditKey;
    }

    public String getHostToken() {
        return hostToken;
    }

    public void setHostToken(String hostToken) {
        this.hostToken = hostToken;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public void setPlayers(List<Player> players) {
        this.players = players == null ? new ArrayList<>() : new ArrayList<>(players);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
