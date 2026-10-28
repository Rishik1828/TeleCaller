package com.smartdialer.repository;

import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallRepository extends JpaRepository<Call, Long> {

    Optional<Call> findByCallExternalId(String callExternalId);

    Optional<Call> findByProviderCallId(String providerCallId);

    Optional<Call> findByIdempotencyKey(String idempotencyKey);

    long countByCampaignIdAndState(Long campaignId, CallState state);

    long countByState(CallState state);

    long countByStateIn(Collection<CallState> states);

    long countByCampaignIdAndStateIn(Long campaignId, Collection<CallState> states);

    List<Call> findByCampaignIdAndStateIn(Long campaignId, Collection<CallState> states);

    List<Call> findByAgentIdAndStateIn(Long agentId, Collection<CallState> states);

    @Query("SELECT c FROM Call c WHERE c.state IN ('INITIATED', 'RINGING', 'ANSWERED') AND c.leaseExpiresAt < :now")
    List<Call> findStaleActiveCalls(@Param("now") Instant now);

    @Query("SELECT AVG(c.durationSeconds) FROM Call c WHERE c.campaignId = :campaignId AND c.state = 'COMPLETED' AND c.durationSeconds > 0")
    Double findAverageTalkDurationSeconds(@Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(c) FROM Call c WHERE c.campaignId = :campaignId AND c.state IN ('ANSWERED', 'CONNECTED', 'COMPLETED')")
    long countAnsweredCalls(@Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(c) FROM Call c WHERE c.campaignId = :campaignId AND c.state IN ('ANSWERED', 'CONNECTED', 'COMPLETED', 'FAILED')")
    long countFinishedAttempts(@Param("campaignId") Long campaignId);
}
