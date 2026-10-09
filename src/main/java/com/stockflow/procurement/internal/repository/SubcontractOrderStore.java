package com.stockflow.procurement.internal.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * SUBCONTRACT purchase orders on the new tables (SCRUM-434). Interim, like
 * {@code ReceivingPurchaseOrderAdapter}: phase P4 of the C4 plan moves the purchase order itself onto
 * these tables, and its repository then takes this over.
 */
public interface SubcontractOrderStore {

    record Row(UUID id, String poNumber, UUID productionOrderId, UUID supplierId, UUID warehouseId, String status,
               UUID inventoryItemId, BigDecimal quantity, BigDecimal unitPrice, String currency,
               BigDecimal totalAmount, LocalDate expectedDate) {
    }

    record Supplier(UUID id, String code, String name, boolean printSubcontractor, BigDecimal lossTolerancePercent) {
    }

    record NewOrder(UUID id, String poNumber, UUID productionOrderId, UUID supplierId, UUID warehouseId,
                    String currency, LocalDate orderDate, LocalDate expectedDate, UUID inventoryItemId,
                    int quantity, BigDecimal unitPrice, UUID createdBy) {
    }

    Optional<Row> findLiveByProductionOrder(UUID productionOrderId);

    Optional<Row> findById(UUID purchaseOrderId, boolean forUpdate);

    Optional<Supplier> supplier(UUID supplierId);

    /** {@code SPO-yyyyMMdd-nnnn}, numbered per business day. */
    String nextNumber(LocalDate day);

    void insert(NewOrder order);

    /** Sets the single line's quantity and recomputes line and header totals; one REVISED event. */
    void changeQuantity(Row order, int newQuantity, String reason, UUID actorId);
}
