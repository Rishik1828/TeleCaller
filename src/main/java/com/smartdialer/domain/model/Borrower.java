package com.smartdialer.domain.model;

import com.smartdialer.domain.enums.BorrowerStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "borrowers", indexes = {
    @Index(name = "idx_borrower_status_camp", columnList = "campaignId, status, priority"),
    @Index(name = "idx_borrower_ext_id", columnList = "borrowerExternalId", unique = true)
})
public class Borrower {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false, unique = true, length = 64)
    private String borrowerExternalId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String phoneNumber;

    @Column(nullable = false)
    private int priority = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BorrowerStatus status = BorrowerStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private int maxAttempts = 3;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(length = 255)
    private String lastOutcome;

    @Column(name = "allocated_call_id")
    private Long allocatedCallId;

    @Version
    private Long version;

    public Borrower() {}

    public Borrower(Long campaignId, String borrowerExternalId, String name, String phoneNumber, int priority) {
        this.campaignId = campaignId;
        this.borrowerExternalId = borrowerExternalId;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.priority = priority;
        this.status = BorrowerStatus.PENDING;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public String getBorrowerExternalId() { return borrowerExternalId; }
    public void setBorrowerExternalId(String borrowerExternalId) { this.borrowerExternalId = borrowerExternalId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public BorrowerStatus getStatus() { return status; }
    public void setStatus(BorrowerStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(String lastOutcome) { this.lastOutcome = lastOutcome; }

    public Long getAllocatedCallId() { return allocatedCallId; }
    public void setAllocatedCallId(Long allocatedCallId) { this.allocatedCallId = allocatedCallId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public void incrementAttempts(String outcome) {
        this.attempts++;
        this.lastOutcome = outcome;
    }
}
