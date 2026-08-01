package com.thavalon.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * One line in a game's audit trail.
 *
 * @param at      when it happened
 * @param gameId  the game it belongs to
 * @param type    what happened
 * @param actor   the player who caused it, where there is one
 * @param detail  event-specific payload. For {@link AuditEventType#GAME_STARTED} this holds the
 *                full role assignment under {@code roles}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEvent(
        Instant at,
        String gameId,
        AuditEventType type,
        String actor,
        Map<String, Object> detail) {

    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
