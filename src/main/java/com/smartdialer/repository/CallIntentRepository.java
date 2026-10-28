package com.smartdialer.repository;

import com.smartdialer.domain.model.CallIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallIntentRepository extends JpaRepository<CallIntent, Long> {

    Optional<CallIntent> findByCallExternalId(String callExternalId);

    Optional<CallIntent> findByIdempotencyKey(String idempotencyKey);

    List<CallIntent> findByDispatchedFalseAndCreatedAtBefore(Instant threshold);
}
