package com.smartdialer.domain.model;

import com.smartdialer.domain.enums.AgentState;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agents", indexes = {
    @Index(name = "idx_agent_state", columnList = "state"),
    @Index(name = "idx_agent_ext_id", columnList = "agentExternalId", unique = true)
})
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String agentExternalId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentState state = AgentState.OFFLINE;

    @Column(nullable = false)
    private Instant lastStateChange = Instant.now();

    @Column(name = "current_call_id")
    private Long currentCallId;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(nullable = false)
    private long totalTalkSeconds = 0;

    @Column(nullable = false)
    private int totalCallsHandled = 0;

    @Version
    private Long version;

    public Agent() {}

    public Agent(String agentExternalId, String name, AgentState state) {
        this.agentExternalId = agentExternalId;
        this.name = name;
        this.state = state;
        this.lastStateChange = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAgentExternalId() { return agentExternalId; }
    public void setAgentExternalId(String agentExternalId) { this.agentExternalId = agentExternalId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AgentState getState() { return state; }
    public void setState(AgentState state) { 
        this.state = state; 
        this.lastStateChange = Instant.now();
    }

    public Instant getLastStateChange() { return lastStateChange; }
    public void setLastStateChange(Instant lastStateChange) { this.lastStateChange = lastStateChange; }

    public Long getCurrentCallId() { return currentCallId; }
    public void setCurrentCallId(Long currentCallId) { this.currentCallId = currentCallId; }

    public Instant getReservationExpiresAt() { return reservationExpiresAt; }
    public void setReservationExpiresAt(Instant reservationExpiresAt) { this.reservationExpiresAt = reservationExpiresAt; }

    public long getTotalTalkSeconds() { return totalTalkSeconds; }
    public void setTotalTalkSeconds(long totalTalkSeconds) { this.totalTalkSeconds = totalTalkSeconds; }

    public int getTotalCallsHandled() { return totalCallsHandled; }
    public void setTotalCallsHandled(int totalCallsHandled) { this.totalCallsHandled = totalCallsHandled; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public boolean isReservationExpired(Instant now) {
        return state == AgentState.RESERVED && reservationExpiresAt != null && now.isAfter(reservationExpiresAt);
    }
}
