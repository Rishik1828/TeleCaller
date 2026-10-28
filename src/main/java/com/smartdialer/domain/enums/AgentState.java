package com.smartdialer.domain.enums;

/**
 * Explicit agent lifecycle states.
 * Transition path: AVAILABLE -> RESERVED -> DIALING -> CONNECTED -> WRAP_UP -> AVAILABLE
 * Disconnect or Pause: Any active or available state -> OFFLINE or PAUSED.
 */
public enum AgentState {
    OFFLINE,
    AVAILABLE,
    RESERVED,
    DIALING,
    CONNECTED,
    WRAP_UP,
    PAUSED
}
