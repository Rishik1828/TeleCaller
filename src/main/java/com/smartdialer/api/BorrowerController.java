package com.smartdialer.api;

import com.smartdialer.api.dto.ApiDtos.CreateBorrowerRequest;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.repository.BorrowerRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BorrowerController {

    private final BorrowerRepository borrowerRepository;

    public BorrowerController(BorrowerRepository borrowerRepository) {
        this.borrowerRepository = borrowerRepository;
    }

    @PostMapping("/campaigns/{campaignId}/borrowers")
    public ResponseEntity<Borrower> addBorrowerToCampaign(@PathVariable Long campaignId,
                                                          @Valid @RequestBody CreateBorrowerRequest req) {
        Borrower b = new Borrower(
            campaignId,
            req.borrowerExternalId(),
            req.name(),
            req.phoneNumber(),
            req.priority() != null ? req.priority() : 1
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(borrowerRepository.save(b));
    }

    @GetMapping("/campaigns/{campaignId}/borrowers")
    public ResponseEntity<List<Borrower>> listBorrowersForCampaign(@PathVariable Long campaignId) {
        return ResponseEntity.ok(borrowerRepository.findByCampaignId(campaignId));
    }

    @GetMapping("/borrowers/{id}")
    public ResponseEntity<Borrower> getBorrower(@PathVariable Long id) {
        return borrowerRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
