package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side of goods receipts for the list screen. Rows carry no lines. */
public interface GoodsReceiptSearch {

    /** {@code withoutPurchaseOrder}: true keeps only receipts without a PO (SCRUM-478), false only those against one. */
    record Criteria(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId, String number,
                    Instant receivedFrom, Instant receivedTo, Boolean withoutPurchaseOrder) {
    }

    /** {@code supplierId}: the supplier of a receipt without a PO; null on one against a PO. */
    record Row(UUID id, String number, UUID purchaseOrderId, UUID supplierId, UUID warehouseId,
               GoodsReceiptStatus status,
               String deliveryNote, Instant receivedAt, UUID receivedBy, Instant confirmedAt, UUID confirmedBy,
               Instant closedAt) {
    }

    /** What a receipt shows of its purchase order: number and supplier (SCRUM-464). */
    record OrderLabel(String purchaseOrderNumber, UUID supplierId, String supplierName) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /** One query for a page of receipts: purchase order id → number and supplier. */
    java.util.Map<UUID, OrderLabel> orderLabels(java.util.Collection<UUID> purchaseOrderIds);

    /** One query: supplier id → name, for the receipts of a page that have no PO to take it from. */
    java.util.Map<UUID, String> supplierNames(java.util.Collection<UUID> supplierIds);
}
