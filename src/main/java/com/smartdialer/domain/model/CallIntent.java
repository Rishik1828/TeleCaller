package com.smartdialer.domain.model;

import com.smartdialer.domain.enums.ProviderType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "call_intents", indexes = {
    @Index(name = "idx_intent_call_ext_id", columnList = "callExternalId", unique = true),
    @Index(name = "idx_intent_idempotency", columnList = "idempotencyKey", unique = true),
    @Index(name = "idx_intent_dispatched", columnList = "dispatched, createdAt")
})
public class CallIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String callExternalId;

    @Column(nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long borrowerId;

    @Column(name = "agent_id")
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderType providerType;

    @Column(nullable = false)
    private boolean dispatched = false;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public CallIntent() {}

    public CallIntent(String callExternalId, String idempotencyKey, Long campaignId, 
                      Long borrowerId, Long agentId, ProviderType providerType) {
        this.callExternalId = callExternalId;
        this.idempotencyKey = idempotencyKey;
        this.campaignId = campaignId;
        this.borrowerId = borrowerId;
        this.agentId = agentId;
        this.providerType = providerType;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCallExternalId() { return callExternalId; }
    public void setCallExternalId(String callExternalId) { this.callExternalId = callExternalId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public Long getBorrowerId() { return borrowerId; }
    public void setBorrowerId(Long borrowerId) { this.borrowerId = borrowerId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public ProviderType getProviderType() { return providerType; }
    public void setProviderType(ProviderType providerType) { this.providerType = providerType; }

    public boolean isDispatched() { return dispatched; }
    public void setDispatched(boolean dispatched) { this.dispatched = dispatched; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
