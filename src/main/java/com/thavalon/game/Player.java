package com.thavalon.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.thavalon.domain.Role;

import java.util.List;

/**
 * A player in a game session.
 *
 * <p>The {@code token} is the player's private credential, minted at join time and kept in the
 * browser's localStorage. It is what lets someone whose phone locked or whose tab closed come
 * back to the same role instead of erroring or joining twice. It is persisted in snapshots and
 * is never included in any response describing <em>other</em> players.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Player {

    private String name;
    private String token;
    private Role role;
    private boolean assassin;
    private boolean roleViewed;
    private List<String> info = List.of();

    public Player() {
    }

    public Player(String name, String token) {
        this.name = name;
        this.token = token;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isAssassin() {
        return assassin;
    }

    public void setAssassin(boolean assassin) {
        this.assassin = assassin;
    }

    /** Whether this player has opened their card since the deal. Drives the lobby's readiness view. */
    public boolean isRoleViewed() {
        return roleViewed;
    }

    public void setRoleViewed(boolean roleViewed) {
        this.roleViewed = roleViewed;
    }

    public List<String> getInfo() {
        return info;
    }

    public void setInfo(List<String> info) {
        this.info = info == null ? List.of() : List.copyOf(info);
    }
}
