package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends AggregateRepository<PurchaseOrder, PurchaseOrderId> {

    /**
     * {@code nextPoNumber} sits here rather than in a utility class because generating the next
     * number is a persistence concern — it needs a database sequence to stay unique across
     * instances. Same reasoning as {@code order.internal.domain.OrderRepository.nextOrderNumber}.
     */
    String nextPoNumber(LocalDate date);

    /** Empty when no such supplier exists. Category has no aggregate/port yet, hosted here — same
     *  precedent as {@code product.internal.domain.ProductRepository.categoryExists}. */
    Optional<SupplierStatus> supplierStatus(UUID supplierId);

    /**
     * Candidates for BR-PO-003 ("two POs to the same supplier, same SKU, same delivery date are
     * flagged as possible duplicates") — non-terminal orders for this supplier due the same date.
     * The caller checks the SKU overlap itself, since the port stays free of the {@code Sku} value
     * object's formatting concerns and this keeps the query simple.
     */
    List<PurchaseOrder> findOpenBySupplierAndExpectedAt(UUID supplierId, LocalDate expectedAt);

    Optional<PurchaseOrder> findByIdForUpdate(PurchaseOrderId id);
}
