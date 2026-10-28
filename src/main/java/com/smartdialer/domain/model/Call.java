package com.smartdialer.domain.model;

import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.ProviderType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "calls", indexes = {
    @Index(name = "idx_call_ext_id", columnList = "callExternalId", unique = true),
    @Index(name = "idx_call_idempotency", columnList = "idempotencyKey", unique = true),
    @Index(name = "idx_call_prov_id", columnList = "providerCallId"),
    @Index(name = "idx_call_state_camp", columnList = "campaignId, state"),
    @Index(name = "idx_call_agent_state", columnList = "agentId, state")
})
public class Call {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String callExternalId;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long borrowerId;

    @Column(name = "agent_id")
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CallState state = CallState.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderType providerType;

    @Column(length = 64)
    private String providerCallId;

    @Column(nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "ringing_at")
    private Instant ringingAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds = 0L;

    @Column(length = 255)
    private String failureReason;

    @Column(nullable = false)
    private boolean abandoned = false;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Version
    private Long version;

    public Call() {}

    public Call(String callExternalId, Long campaignId, Long borrowerId, Long agentId,
                ProviderType providerType, String idempotencyKey) {
        this.callExternalId = callExternalId;
        this.campaignId = campaignId;
        this.borrowerId = borrowerId;
        this.agentId = agentId;
        this.providerType = providerType;
        this.idempotencyKey = idempotencyKey;
        this.state = CallState.QUEUED;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCallExternalId() { return callExternalId; }
    public void setCallExternalId(String callExternalId) { this.callExternalId = callExternalId; }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public Long getBorrowerId() { return borrowerId; }
    public void setBorrowerId(Long borrowerId) { this.borrowerId = borrowerId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public CallState getState() { return state; }
    public void setState(CallState state) { this.state = state; }

    public ProviderType getProviderType() { return providerType; }
    public void setProviderType(ProviderType providerType) { this.providerType = providerType; }

    public String getProviderCallId() { return providerCallId; }
    public void setProviderCallId(String providerCallId) { this.providerCallId = providerCallId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(Instant initiatedAt) { this.initiatedAt = initiatedAt; }

    public Instant getRingingAt() { return ringingAt; }
    public void setRingingAt(Instant ringingAt) { this.ringingAt = ringingAt; }

    public Instant getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }

    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public boolean isAbandoned() { return abandoned; }
    public void setAbandoned(boolean abandoned) { this.abandoned = abandoned; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
