package com.smartdialer.statemachine;

public record TransitionResult(
    boolean successful,
    String fromState,
    String toState,
    String message
) {
    public static TransitionResult success(Enum<?> from, Enum<?> to, String message) {
        return new TransitionResult(true, from != null ? from.name() : "NONE", to.name(), message);
    }

    public static TransitionResult failed(String message) {
        return new TransitionResult(false, null, null, message);
    }
}
