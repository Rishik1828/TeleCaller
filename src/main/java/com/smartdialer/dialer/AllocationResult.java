package com.smartdialer.dialer;

public record AllocationResult(
    int requestedCalls,
    int successfullyInitiated,
    int failedAllocations,
    String summary
) {}
