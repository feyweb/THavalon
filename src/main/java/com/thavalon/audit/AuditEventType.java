package com.thavalon.audit;

public enum AuditEventType {
    GAME_CREATED,
    PLAYER_JOINED,
    PLAYER_LEFT,
    /** Removed from the lobby by the host, rather than leaving of their own accord. */
    PLAYER_KICKED,
    HOST_TRANSFERRED,
    GAME_STARTED,
    ROLE_VIEWED,
    GAME_EXPIRED
}
