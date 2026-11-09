package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port of production orders (4A-2). */
public interface ProductionOrderRepository {

    Optional<ProductionOrder> findById(UUID id);

    /** The production order, its row locked until the transaction ends and read fresh. */
    Optional<ProductionOrder> findByIdForUpdate(UUID id);

    /** The production orders with these ids, unlocked; unknown ids are left out. */
    List<ProductionOrder> findAllById(Collection<UUID> ids);

    /** Every production order of a sales order, oldest first. */
    List<ProductionOrder> ofSalesOrder(UUID salesOrderId);

    /** The next code, {@code PRD-yyyyMMdd-nnnn} (QD-6), from {@code platform.document_sequence}. */
    String nextCode(LocalDate day);

    /** Of these SKUs, those some production order has started mass printing of (BR-24: produced before). */
    Set<String> producedBefore(Collection<String> skus);

    /**
     * Writes a new production order, or the changes of one read before at its version, with its lines (rewritten
     * while it is a draft) and its changes of status.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    ProductionOrder save(ProductionOrder order);

    /** One change of status as kept, for the order's history. */
    record HistoryEntry(ProductionOrderStatus from, ProductionOrderStatus to, String reason, UUID by,
                        java.time.Instant at) {
    }

    List<HistoryEntry> history(UUID productionOrderId);
}
