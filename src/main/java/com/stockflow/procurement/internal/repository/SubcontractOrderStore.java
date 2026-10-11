package com.stockflow.procurement.internal.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * SUBCONTRACT purchase orders (SCRUM-434) as the subcontracting flow sees them: one order per
 * production order, one line. A narrow view on purpose — the flow raises the order and resizes its
 * line while it is a draft; submitting, approving and receiving it are the ordinary purchase-order
 * paths. The adapter reads and writes through the purchase order's own JPA entities.
 */
public interface SubcontractOrderStore {

    record Row(UUID id, String poNumber, UUID productionOrderId, UUID supplierId, UUID warehouseId, String status,
               UUID inventoryItemId, BigDecimal quantity, BigDecimal unitPrice, String currency,
               BigDecimal totalAmount, LocalDate expectedDate) {
    }

    /** The supplier as subcontracting needs it, with the commercial terms a new order captures. */
    record Supplier(UUID id, String code, String name, boolean printSubcontractor, BigDecimal lossTolerancePercent,
                    int paymentTermDays, int leadTimeDays) {
    }

    record NewOrder(String poNumber, UUID productionOrderId, Supplier supplier, UUID warehouseId,
                    String currency, LocalDate orderDate, LocalDate expectedDate, UUID inventoryItemId,
                    String sku, String uom, int quantity, BigDecimal unitPrice, UUID createdBy) {
    }

    Optional<Row> findLiveByProductionOrder(UUID productionOrderId);

    Optional<Row> findById(UUID purchaseOrderId, boolean forUpdate);

    Optional<Supplier> supplier(UUID supplierId);

    /** {@code SPO-yyyyMMdd-nnnn}, numbered per business day. */
    String nextNumber(LocalDate day);

    /** Inserts the DRAFT order with its one line and a CREATED event; returns the order's id. */
    UUID insert(NewOrder order);

    /**
     * Sets the DRAFT order's single line to {@code newQuantity} and recomputes line and header totals;
     * one REVISED event. Refused (by the entity) once the order has left DRAFT.
     */
    void changeQuantity(Row order, int newQuantity, String reason, UUID actorId);
}
