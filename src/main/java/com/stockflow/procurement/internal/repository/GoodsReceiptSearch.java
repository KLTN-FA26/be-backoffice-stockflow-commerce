package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side of goods receipts for the list screen. Rows carry no lines. */
public interface GoodsReceiptSearch {

    record Criteria(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId, String number,
                    Instant receivedFrom, Instant receivedTo) {
    }

    record Row(UUID id, String number, UUID purchaseOrderId, UUID warehouseId, GoodsReceiptStatus status,
               String deliveryNote, Instant receivedAt, UUID receivedBy, Instant confirmedAt, Instant closedAt) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);
}
