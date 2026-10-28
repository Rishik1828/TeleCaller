package com.smartdialer.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "call_event_logs", indexes = {
    @Index(name = "idx_event_prov_event_id", columnList = "providerEventId", unique = true),
    @Index(name = "idx_event_call_ext_id", columnList = "callExternalId")
})
public class CallEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String providerEventId;

    @Column(nullable = false, length = 64)
    private String providerCallId;

    @Column(length = 64)
    private String callExternalId;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Column(length = 1024)
    private String payload;

    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(nullable = false)
    private boolean applied = false;

    @Column(length = 255)
    private String processingNote;

    public CallEventLog() {}

    public CallEventLog(String providerEventId, String providerCallId, String callExternalId, 
                        String eventType, String payload) {
        this.providerEventId = providerEventId;
        this.providerCallId = providerCallId;
        this.callExternalId = callExternalId;
        this.eventType = eventType;
        this.payload = payload;
        this.receivedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }

    public String getProviderCallId() { return providerCallId; }
    public void setProviderCallId(String providerCallId) { this.providerCallId = providerCallId; }

    public String getCallExternalId() { return callExternalId; }
    public void setCallExternalId(String callExternalId) { this.callExternalId = callExternalId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public String getProcessingNote() { return processingNote; }
    public void setProcessingNote(String processingNote) { this.processingNote = processingNote; }
}
