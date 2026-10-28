package com.smartdialer.api;

import com.smartdialer.api.dto.ApiDtos.ProviderEventRequest;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.recovery.RecoveryAndRetryManager;
import com.smartdialer.safety.SafetyController;
import com.smartdialer.safety.SafetyDecision;
import com.smartdialer.telecom.ProviderEvent;
import com.smartdialer.telecom.ProviderHealth;
import com.smartdialer.telecom.TelecomProvider;
import com.smartdialer.telecom.TelecomProviderRegistry;
import com.smartdialer.telecom.event.ProviderEventProcessor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProviderEventController {

    private final ProviderEventProcessor eventProcessor;
    private final TelecomProviderRegistry providerRegistry;
    private final SafetyController safetyController;
    private final RecoveryAndRetryManager recoveryManager;

    public ProviderEventController(ProviderEventProcessor eventProcessor,
                                   TelecomProviderRegistry providerRegistry,
                                   SafetyController safetyController,
                                   RecoveryAndRetryManager recoveryManager) {
        this.eventProcessor = eventProcessor;
        this.providerRegistry = providerRegistry;
        this.safetyController = safetyController;
        this.recoveryManager = recoveryManager;
    }

    @PostMapping("/provider-events")
    public ResponseEntity<Map<String, Object>> injectProviderEvent(@Valid @RequestBody ProviderEventRequest req) {
        ProviderEvent event = ProviderEvent.of(
            req.providerEventId(),
            req.providerCallId(),
            req.callExternalId(),
            req.eventType(),
            req.failureReason(),
            req.durationSeconds()
        );

        eventProcessor.onEvent(event);

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "RECEIVED");
        resp.put("providerEventId", req.providerEventId());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/providers/health")
    public ResponseEntity<Map<String, ProviderHealth>> getProvidersHealth() {
        Map<String, ProviderHealth> result = new HashMap<>();
        for (Map.Entry<ProviderType, TelecomProvider> entry : providerRegistry.getAllProviders().entrySet()) {
            result.put(entry.getKey().name(), entry.getValue().getProviderHealth());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/safety/decisions")
    public ResponseEntity<List<SafetyDecision>> getSafetyDecisions() {
        return ResponseEntity.ok(safetyController.getRecentDecisions());
    }

    @PostMapping("/recovery/run")
    public ResponseEntity<RecoveryAndRetryManager.RecoverySummary> triggerRecovery() {
        RecoveryAndRetryManager.RecoverySummary summary = recoveryManager.runPeriodicRecovery();
        return ResponseEntity.ok(summary);
    }
}
