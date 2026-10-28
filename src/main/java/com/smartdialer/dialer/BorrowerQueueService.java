package com.smartdialer.dialer;

import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.repository.BorrowerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Borrower Queue Service:
 * Manages priority-based retrieval and atomic reservation of borrowers.
 */
@Service
public class BorrowerQueueService {

    private final BorrowerRepository borrowerRepository;

    public BorrowerQueueService(BorrowerRepository borrowerRepository) {
        this.borrowerRepository = borrowerRepository;
    }

    @Transactional
    public boolean tryReserveBorrower(Long borrowerId, Long expectedVersion) {
        int updated = borrowerRepository.atomicTransition(
            borrowerId,
            BorrowerStatus.PENDING,
            BorrowerStatus.RESERVED,
            expectedVersion
        );
        return updated == 1;
    }

    @Transactional
    public Borrower claimNextEligibleBorrower(Long campaignId) {
        List<Borrower> candidates = borrowerRepository.findEligibleBorrowers(
            campaignId, Instant.now(), PageRequest.of(0, 10)
        );

        for (Borrower b : candidates) {
            int updated = borrowerRepository.atomicTransition(
                b.getId(),
                b.getStatus(),
                BorrowerStatus.RESERVED,
                b.getVersion()
            );
            if (updated == 1) {
                return borrowerRepository.findById(b.getId()).orElse(b);
            }
        }
        return null;
    }
}
