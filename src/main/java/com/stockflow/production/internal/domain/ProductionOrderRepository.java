package com.stockflow.production.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of production orders ({@code production.production_order}). */
public interface ProductionOrderRepository {

    Optional<ProductionOrder> findById(UUID id);

    /** The root production order of a sample request or an order line (subcontracted children excluded). */
    Optional<ProductionOrder> findBySource(ProductionOrder.Type type, UUID sourceId);

    /**
     * Creates the order unless its source already has one, and returns whichever order the source has
     * afterwards — the caller compares it with what it asked for. Must be safe against a concurrent
     * creator of the same source: the second caller waits for the first to commit and gets its order,
     * it does not insert a second one or fail on the unique index (BR-PRD-01). Records the first status
     * history row of an order it creates, at {@code at} and attributed to {@code createdBy} (null for the
     * system).
     */
    ProductionOrder createIfAbsent(ProductionOrder candidate, UUID createdBy, Instant at);

    /**
     * BR-PRD-08, repeat design: whether an ORDER production order of this design reached COMPLETED for
     * another sales order. Mirrors the order module's own check, which reads the
     * {@code ProductionCompleted} events this module publishes.
     */
    boolean completedForAnotherOrder(UUID designSnapshotId, UUID excludingSalesOrderId);

    /** {@code LSX-yyyyMMdd-nnnn} from {@code platform.document_sequence}, in the caller's transaction. */
    String nextNumber(LocalDate day);
}
