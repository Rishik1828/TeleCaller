package com.smartdialer.api;

import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.model.Call;
import com.smartdialer.repository.CallRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/calls")
public class CallController {

    private final CallRepository callRepository;

    public CallController(CallRepository callRepository) {
        this.callRepository = callRepository;
    }

    @GetMapping
    public ResponseEntity<List<Call>> listCalls(@RequestParam(required = false) Long campaignId,
                                                @RequestParam(required = false) CallState state) {
        if (campaignId != null && state != null) {
            return ResponseEntity.ok(callRepository.findByCampaignIdAndStateIn(campaignId, List.of(state)));
        } else if (campaignId != null) {
            return ResponseEntity.ok(callRepository.findByCampaignIdAndStateIn(campaignId, List.of(CallState.values())));
        }
        return ResponseEntity.ok(callRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Call> getCall(@PathVariable Long id) {
        return callRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
