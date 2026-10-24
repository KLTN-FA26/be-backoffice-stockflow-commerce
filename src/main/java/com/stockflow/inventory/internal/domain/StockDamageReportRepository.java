package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link StockDamageReport}. Listing lives on the search side, not here. */
public interface StockDamageReportRepository {

    Optional<StockDamageReport> findById(UUID id);

    /**
     * The report, its row locked until the transaction ends: two decisions on one report are taken one after the
     * other, so neither decides units the other already did.
     */
    Optional<StockDamageReport> findByIdForUpdate(UUID id);

    /** The report a request made, if it was made: how a retried report is recognised. */
    Optional<StockDamageReport> findByRequestId(UUID requestId);

    /** {@code DMG-yyyyMMdd-0001}: the damage record's number, from a per-day counter that never repeats. */
    String nextReportNumber(LocalDate day);

    /** {@code WO-yyyyMMdd-0001}: the destruction record of a write-off, from its own per-day counter. */
    String nextWriteOffNumber(LocalDate day);

    StockDamageReport save(StockDamageReport report);
}
