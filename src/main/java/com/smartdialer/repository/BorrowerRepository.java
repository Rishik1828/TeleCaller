package com.smartdialer.repository;

import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.model.Borrower;
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
public interface BorrowerRepository extends JpaRepository<Borrower, Long> {

    Optional<Borrower> findByBorrowerExternalId(String borrowerExternalId);

    List<Borrower> findByCampaignId(Long campaignId);

    long countByCampaignIdAndStatus(Long campaignId, BorrowerStatus status);

    @Query("SELECT b FROM Borrower b WHERE b.campaignId = :campaignId AND " +
           "(b.status = 'PENDING' OR (b.status = 'RETRY_SCHEDULED' AND b.nextRetryAt <= :now)) " +
           "ORDER BY b.priority DESC, b.id ASC")
    List<Borrower> findEligibleBorrowers(@Param("campaignId") Long campaignId,
                                        @Param("now") Instant now,
                                        Pageable pageable);

    @Modifying
    @Query("UPDATE Borrower b SET b.status = :targetStatus, b.version = b.version + 1 " +
           "WHERE b.id = :id AND b.status = :expectedStatus AND b.version = :expectedVersion")
    int atomicTransition(@Param("id") Long id,
                         @Param("expectedStatus") BorrowerStatus expectedStatus,
                         @Param("targetStatus") BorrowerStatus targetStatus,
                         @Param("expectedVersion") Long expectedVersion);
}
