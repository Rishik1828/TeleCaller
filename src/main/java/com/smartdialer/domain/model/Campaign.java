package com.smartdialer.domain.model;

import com.smartdialer.domain.enums.CampaignState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CampaignState state = CampaignState.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DialingMode dialingMode = DialingMode.PROGRESSIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderType activeProvider = ProviderType.MOCK_PROVIDER_A;

    @Column(nullable = false)
    private double targetAnswerRate = 0.35;

    @Column(nullable = false)
    private int pacingIntervalMs = 2000;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Campaign() {}

    public Campaign(String name, DialingMode dialingMode, ProviderType activeProvider) {
        this.name = name;
        this.dialingMode = dialingMode;
        this.activeProvider = activeProvider;
        this.state = CampaignState.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public CampaignState getState() { return state; }
    public void setState(CampaignState state) { 
        this.state = state; 
        this.updatedAt = Instant.now();
    }

    public DialingMode getDialingMode() { return dialingMode; }
    public void setDialingMode(DialingMode dialingMode) { this.dialingMode = dialingMode; }

    public ProviderType getActiveProvider() { return activeProvider; }
    public void setActiveProvider(ProviderType activeProvider) { this.activeProvider = activeProvider; }

    public double getTargetAnswerRate() { return targetAnswerRate; }
    public void setTargetAnswerRate(double targetAnswerRate) { this.targetAnswerRate = targetAnswerRate; }

    public int getPacingIntervalMs() { return pacingIntervalMs; }
    public void setPacingIntervalMs(int pacingIntervalMs) { this.pacingIntervalMs = pacingIntervalMs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
