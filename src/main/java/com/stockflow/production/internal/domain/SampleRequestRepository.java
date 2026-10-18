package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of sample rounds ({@code production.sample_request}). */
public interface SampleRequestRepository {

    /**
     * Loads the round and locks its row until the transaction ends: two people answering the same
     * sample, or Sales submitting while someone cancels, are serialised here rather than raced.
     */
    Optional<SampleRequest> findByIdForUpdate(UUID id);

    Optional<SampleRequest> findById(UUID id);

    /** The round that followed this one, when there is one ({@code uk_sample_request_next_round}). */
    Optional<UUID> nextRoundOf(UUID id);

    /** The latest APPROVED round of this design on this blank, the QC reference of a repeat line. */
    Optional<SampleRequest> latestApproved(UUID designSnapshotId, Sku blankSku);

    /** Inserts a new round or writes the state of an existing one, flushed so a constraint fails here. */
    SampleRequest save(SampleRequest sample);

    /** {@code SR-yyyyMMdd-nnnn} from {@code platform.document_sequence}, in the caller's transaction. */
    String nextNumber(LocalDate day);
}
