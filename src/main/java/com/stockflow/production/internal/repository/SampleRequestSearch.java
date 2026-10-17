package com.stockflow.production.internal.repository;

import com.stockflow.production.internal.domain.SampleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of sample rounds: the list, one round with its audit fields, and the chain of rounds. */
public interface SampleRequestSearch {

    /**
     * @param customerId           forced to the caller's own customer for a portal user
     * @param restrictToWarehouses when not null, only rounds whose SAMPLE production order is made in one of
     *                             these warehouses (a warehouse-bound workshop user); empty matches nothing
     */
    record Criteria(UUID customerId, List<SampleStatus> statuses, UUID designSnapshotId, String number,
                    Collection<UUID> restrictToWarehouses) {
    }

    record Row(UUID id, String requestNumber, UUID customerId, UUID quoteId, UUID previousRoundId, int round,
               SampleStatus status, String blankSku, UUID designSnapshotId, int quantity, LocalDate dueDate,
               Instant sentAt, Instant respondedAt, UUID respondedBy, String customerFeedback,
               String cancellationReason, Instant createdAt, String createdBy, Instant lastModifiedAt,
               String lastModifiedBy, long version) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    Optional<Row> findRow(UUID id);

    /** Every round of the request this round belongs to, first round first. */
    List<Row> rounds(UUID id);
}
