package com.smartdialer.domain.enums;

/**
 * Explicit call lifecycle states.
 * Typical path: QUEUED -> RESERVED -> INITIATED -> RINGING -> ANSWERED -> CONNECTED -> COMPLETED
 * Terminal states: COMPLETED, FAILED, CANCELLED
 */
public enum CallState {
    QUEUED,
    RESERVED,
    INITIATED,
    RINGING,
    ANSWERED,
    CONNECTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
