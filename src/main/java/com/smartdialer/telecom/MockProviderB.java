package com.smartdialer.telecom;

import com.smartdialer.domain.enums.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockProviderB implements TelecomProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProviderB.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final List<ProviderEventListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Simulation controls
    private volatile double simulatedFailureRate = 12.0; // 12% default failures/timeouts
    private volatile long simulatedLatencyMs = 450;
    private volatile double simulatedAnswerRate = 0.35;
    private volatile long simulatedTalkDurationSeconds = 30;

    // Edge-case toggles for testing
    private volatile boolean injectDuplicateEvents = true;
    private volatile boolean injectOutOfOrderEvents = true;

    private final Set<String> cancelledCalls = ConcurrentHashMap.newKeySet();

    @Override
    public ProviderType getProviderType() {
        return ProviderType.MOCK_PROVIDER_B;
    }

    @Override
    public void registerEventListener(ProviderEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public CallInitiateResponse initiateCall(CallInitiateRequest request) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        // Higher and jittered latency
        long jitter = ThreadLocalRandom.current().nextLong(50, 300);
        try {
            Thread.sleep(Math.max(10, simulatedLatencyMs + jitter));
        } catch (InterruptedException ignored) {}

        long latency = System.currentTimeMillis() - start;
        totalLatencyMs.addAndGet(latency);

        if (ThreadLocalRandom.current().nextDouble(100.0) < simulatedFailureRate) {
            failedRequests.incrementAndGet();
            log.warn("MockProviderB: Simulating timeout/network failure on call {}", request.callExternalId());
            return CallInitiateResponse.rejected("GATEWAY_TIMEOUT_504", latency);
        }

        String providerCallId = "PROV-B-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("MockProviderB: Accepted call {} -> providerCallId: {}", request.callExternalId(), providerCallId);

        scheduleCallLifecycle(request, providerCallId);

        return CallInitiateResponse.success(providerCallId, latency);
    }

    @Override
    public boolean cancelCall(String providerCallId) {
        cancelledCalls.add(providerCallId);
        log.info("MockProviderB: Call {} cancelled", providerCallId);
        emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, null, "CANCELLED", "SAFETY_ABORTED", 0L));
        return true;
    }

    private void scheduleCallLifecycle(CallInitiateRequest request, String providerCallId) {
        // MockProviderB demonstrates out-of-order & duplicate events
        scheduler.schedule(() -> {
            if (isCancelled(providerCallId)) return;

            boolean answered = ThreadLocalRandom.current().nextDouble(1.0) < simulatedAnswerRate;

            if (answered) {
                if (injectOutOfOrderEvents) {
                    // Send COMPLETED immediately, then late ANSWERED to test reconciliation!
                    emitEvent(ProviderEvent.of("EVT-OOF-" + UUID.randomUUID(), providerCallId, request.callExternalId(), "COMPLETED", null, 25L));
                    scheduler.schedule(() -> {
                        emitEvent(ProviderEvent.of("EVT-LATE-" + UUID.randomUUID(), providerCallId, request.callExternalId(), "ANSWERED", null, null));
                    }, 200, TimeUnit.MILLISECONDS);
                } else {
                    // Standard flow with duplicate events
                    emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "ANSWERED", null, null));

                    if (injectDuplicateEvents) {
                        // Send duplicate event with same event ID!
                        String dupEventId = "EVT-DUP-" + UUID.randomUUID();
                        emitEvent(ProviderEvent.of(dupEventId, providerCallId, request.callExternalId(), "ANSWERED", null, null));
                        scheduler.schedule(() -> {
                            emitEvent(ProviderEvent.of(dupEventId, providerCallId, request.callExternalId(), "ANSWERED", null, null));
                        }, 150, TimeUnit.MILLISECONDS);
                    }

                    scheduler.schedule(() -> {
                        if (isCancelled(providerCallId)) return;
                        emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "CONNECTED", null, null));
                        scheduler.schedule(() -> {
                            if (isCancelled(providerCallId)) return;
                            emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "COMPLETED", null, simulatedTalkDurationSeconds));
                        }, 500, TimeUnit.MILLISECONDS);
                    }, 100, TimeUnit.MILLISECONDS);
                }
            } else {
                emitEvent(ProviderEvent.of(UUID.randomUUID().toString(), providerCallId, request.callExternalId(), "FAILED", "NO_ANSWER_TIMEOUT", 0L));
            }
        }, 800, TimeUnit.MILLISECONDS);
    }

    private boolean isCancelled(String providerCallId) {
        return cancelledCalls.contains(providerCallId);
    }

    private void emitEvent(ProviderEvent event) {
        for (ProviderEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error dispatching provider event from MockProviderB", e);
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
            return ProviderHealth.degraded("MockProviderB", failPct, avgLat, total, failed, "HIGH_FAILURE_RATE");
        }
        return ProviderHealth.healthy("MockProviderB", failPct, avgLat, total, failed);
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

    public void setInjectDuplicateEvents(boolean inject) { this.injectDuplicateEvents = inject; }
    public void setInjectOutOfOrderEvents(boolean inject) { this.injectOutOfOrderEvents = inject; }
}
