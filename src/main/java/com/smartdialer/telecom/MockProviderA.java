package com.smartdialer.telecom;

import com.smartdialer.domain.enums.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockProviderA implements TelecomProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProviderA.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final List<ProviderEventListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Configurable simulation parameters
    private volatile double simulatedFailureRate = 2.0; // 2% failure
    private volatile long simulatedLatencyMs = 80;
    private volatile double simulatedAnswerRate = 0.35; // 35% default
    private volatile long simulatedTalkDurationSeconds = 30;

    private final Set<String> cancelledCalls = ConcurrentHashMap.newKeySet();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.MOCK_PROVIDER_A;
    }

    @Override
    public void registerEventListener(ProviderEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public CallInitiateResponse initiateCall(CallInitiateRequest request) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        // Simulate network latency
        try {
            Thread.sleep(Math.max(10, simulatedLatencyMs));
        } catch (InterruptedException ignored) {}

        long latency = System.currentTimeMillis() - start;
        totalLatencyMs.addAndGet(latency);

        // Simulate network-level failure
        if (ThreadLocalRandom.current().nextDouble(100.0) < simulatedFailureRate) {
            failedRequests.incrementAndGet();
            log.warn("MockProviderA: Call {} network failure/timeout", request.callExternalId());
            return CallInitiateResponse.rejected("CARRIER_NETWORK_TIMEOUT", latency);
        }

        String providerCallId = "PROV-A-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("MockProviderA: Accepted call {} -> providerCallId: {}", request.callExternalId(), providerCallId);

        // Schedule asynchronous telephony lifecycle
        scheduleCallLifecycle(request, providerCallId);

        return CallInitiateResponse.success(providerCallId, latency);
    }

    @Override
    public boolean cancelCall(String providerCallId) {
        cancelledCalls.add(providerCallId);
        log.info("MockProviderA: Call {} cancelled", providerCallId);
        emitEvent(ProviderEvent.of(
            UUID.randomUUID().toString(),
            providerCallId,
            null,
            "CANCELLED",
            "USER_OR_SAFETY_ABORTED",
            0L
        ));
        return true;
    }

    private void scheduleCallLifecycle(CallInitiateRequest request, String providerCallId) {
        // 1. INITIATED event (after 100ms)
        scheduler.schedule(() -> {
            if (isCancelled(providerCallId)) return;
            emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "INITIATED", null, null));
        }, 100, TimeUnit.MILLISECONDS);

        // 2. RINGING event (after 500ms)
        scheduler.schedule(() -> {
            if (isCancelled(providerCallId)) return;
            emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "RINGING", null, null));
        }, 500, TimeUnit.MILLISECONDS);

        // 3. Outcome after ringing delay (1200ms)
        scheduler.schedule(() -> {
            if (isCancelled(providerCallId)) return;

            boolean answered = ThreadLocalRandom.current().nextDouble(1.0) < simulatedAnswerRate;
            if (answered) {
                // Borrower Answered
                emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "ANSWERED", null, null));

                // Connected immediately after answer
                scheduler.schedule(() -> {
                    if (isCancelled(providerCallId)) return;
                    emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "CONNECTED", null, null));

                    // Schedule Talk Duration then COMPLETED
                    long talkTime = Math.max(1, simulatedTalkDurationSeconds);
                    scheduler.schedule(() -> {
                        if (isCancelled(providerCallId)) return;
                        emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "COMPLETED", null, talkTime));
                    }, Math.min(talkTime * 100, 3000), TimeUnit.MILLISECONDS); // Compressed for interactive simulation
                }, 100, TimeUnit.MILLISECONDS);

            } else {
                // Not answered (busy, no answer)
                String reason = ThreadLocalRandom.current().nextBoolean() ? "USER_BUSY" : "NO_ANSWER";
                emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "FAILED", reason, 0L));
            }
        }, 1200, TimeUnit.MILLISECONDS);
    }

    private boolean isCancelled(String providerCallId) {
        return cancelledCalls.contains(providerCallId);
    }

    private void emitEvent(ProviderEvent event) {
        for (ProviderEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error dispatching provider event to listener", e);
            }
        }
    }

    @Override
    public ProviderHealth getProviderHealth() {
        long total = totalRequests.get();
        long failed = failedRequests.get();
        double failPct = total > 0 ? ((double) failed / total) * 100.0 : 0.0;
        long avgLat = total > 0 ? (totalLatencyMs.get() / total) : simulatedLatencyMs;

        if (failPct > 20.0 || simulatedFailureRate > 20.0) {
            return ProviderHealth.degraded("MockProviderA", failPct, avgLat, total, failed, "HIGH_FAILURE_RATE");
        }
        return ProviderHealth.healthy("MockProviderA", failPct, avgLat, total, failed);
    }

    @Override
    public void resetMetrics() {
        totalRequests.set(0);
        failedRequests.set(0);
        totalLatencyMs.set(0);
        cancelledCalls.clear();
    }

    @Override public void setSimulatedFailureRate(double rate) { this.simulatedFailureRate = rate; }
    @Override public void setSimulatedLatencyMs(long latency) { this.simulatedLatencyMs = latency; }
    @Override public void setSimulatedAnswerRate(double rate) { this.simulatedAnswerRate = rate; }
    @Override public void setSimulatedTalkDurationSeconds(long duration) { this.simulatedTalkDurationSeconds = duration; }
}
