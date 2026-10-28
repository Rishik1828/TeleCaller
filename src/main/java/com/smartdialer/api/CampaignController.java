package com.smartdialer.api;

import com.smartdialer.api.dto.ApiDtos.CreateCampaignRequest;
import com.smartdialer.dialer.ProgressiveDialer;
import com.smartdialer.domain.enums.CampaignState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Campaign;
import com.smartdialer.repository.CampaignRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignRepository campaignRepository;
    private final ProgressiveDialer progressiveDialer;

    public CampaignController(CampaignRepository campaignRepository, ProgressiveDialer progressiveDialer) {
        this.campaignRepository = campaignRepository;
        this.progressiveDialer = progressiveDialer;
    }

    @PostMapping
    public ResponseEntity<Campaign> createCampaign(@Valid @RequestBody CreateCampaignRequest req) {
        Campaign campaign = new Campaign(
            req.name(),
            req.dialingMode() != null ? req.dialingMode() : DialingMode.PROGRESSIVE,
            req.activeProvider() != null ? req.activeProvider() : ProviderType.MOCK_PROVIDER_A
        );
        if (req.targetAnswerRate() != null) {
            campaign.setTargetAnswerRate(req.targetAnswerRate());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignRepository.save(campaign));
    }

    @GetMapping
    public ResponseEntity<List<Campaign>> listCampaigns() {
        return ResponseEntity.ok(campaignRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Campaign> getCampaign(@PathVariable Long id) {
        return campaignRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Campaign> startCampaign(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));
        campaign.setState(CampaignState.RUNNING);
        return ResponseEntity.ok(campaignRepository.save(campaign));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Campaign> pauseCampaign(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));
        campaign.setState(CampaignState.PAUSED);
        return ResponseEntity.ok(campaignRepository.save(campaign));
    }

    @PostMapping("/{id}/dial-cycle")
    public ResponseEntity<ProgressiveDialer.DialingCycleReport> triggerDialCycle(@PathVariable Long id) {
        ProgressiveDialer.DialingCycleReport report = progressiveDialer.executeDialingCycle(id);
        return ResponseEntity.ok(report);
    }
}
