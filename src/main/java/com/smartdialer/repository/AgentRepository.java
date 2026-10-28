package com.smartdialer.repository;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.model.Agent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByAgentExternalId(String agentExternalId);

    List<Agent> findByState(AgentState state);

    long countByState(AgentState state);

    @Query("SELECT a FROM Agent a WHERE a.state = 'AVAILABLE' ORDER BY a.lastStateChange ASC")
    List<Agent> findAvailableAgents(Pageable pageable);

    @Query("SELECT a FROM Agent a WHERE a.state = 'RESERVED' AND a.reservationExpiresAt IS NOT NULL AND a.reservationExpiresAt < :now")
    List<Agent> findExpiredReservations(@Param("now") Instant now);

    /**
     * Atomic Compare-And-Swap (CAS) update to reserve an agent.
     * Prevents race conditions across concurrent workers without heavy database table locks.
     */
    @Modifying
    @Query("UPDATE Agent a SET a.state = :targetState, a.reservationExpiresAt = :expiresAt, " +
           "a.lastStateChange = :now, a.version = a.version + 1 " +
           "WHERE a.id = :id AND a.state = :expectedState AND a.version = :expectedVersion")
    int atomicTransition(@Param("id") Long id,
                         @Param("expectedState") AgentState expectedState,
                         @Param("targetState") AgentState targetState,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("expiresAt") Instant expiresAt,
                         @Param("now") Instant now);
}
