package com.smartdialer.repository;

import com.smartdialer.domain.model.CallEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CallEventLogRepository extends JpaRepository<CallEventLog, Long> {

    boolean existsByProviderEventId(String providerEventId);

    Optional<CallEventLog> findByProviderEventId(String providerEventId);
}
