package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: e-invoices, each written and read with its lines. */
public interface EInvoiceRepository {

    Optional<EInvoice> findById(UUID id);

    /**
     * The invoice with its row locked until the transaction ends, read afresh: two people issuing one draft, or the
     * accountant and the reconciliation job, are decided one after the other.
     */
    Optional<EInvoice> findByIdForUpdate(UUID id);

    /** The original invoice in force for a shipment - not voided, not replaced (kltn-docs 21 BR-01: at most one). */
    Optional<EInvoice> findLiveOriginalOfShipment(UUID shipmentId);

    /**
     * Writes the invoice against the version it was read at.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    EInvoice save(EInvoice invoice);

    /** Up to {@code limit} invoices waiting for the provider's answer, the longest waiting first. */
    List<UUID> findSubmitting(int limit);

    /**
     * Up to {@code limit} drafts and rejected invoices whose deadline is before {@code dueBefore} and whose accountant
     * was not told yet, the most urgent first.
     */
    List<UUID> findDueUnwarned(Instant dueBefore, int limit);

    /**
     * Up to {@code limit} drafts the system may issue by itself, the nearest deadline first: originals that were never
     * sent and have nothing to look at. A draft that came back from a rejection is not among them - somebody has to
     * correct it first.
     */
    List<UUID> findAutoIssuable(int limit);

    /** The next internal invoice code of {@code day}: {@code EINV-yyyyMMdd-nnnn}. */
    String nextCode(java.time.LocalDate day);
}
