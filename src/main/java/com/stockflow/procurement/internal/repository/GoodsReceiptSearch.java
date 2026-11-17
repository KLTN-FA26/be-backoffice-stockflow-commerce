package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side of goods receipts for the list screen. Rows carry no lines. */
public interface GoodsReceiptSearch {

    /** {@code productionOrderId}, {@code receiptType}: 4A-5; {@code PURCHASE} keeps the historical purchase receipts. */
    record Criteria(List<GoodsReceiptStatus> statuses, UUID warehouseId, String number, Instant receivedFrom,
                    Instant receivedTo, UUID productionOrderId, GoodsReceiptType receiptType) {
    }

    /** {@code productionOrderId}: the production order of a PRODUCTION receipt (4A-5), null on a purchase receipt. */
    record Row(UUID id, String number, UUID warehouseId, GoodsReceiptStatus status, String deliveryNote,
               Instant receivedAt, UUID receivedBy, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
               GoodsReceiptType receiptType, UUID productionOrderId) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);
}
