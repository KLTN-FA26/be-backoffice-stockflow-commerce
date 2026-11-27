package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side of return receipts for the list screen (team plan 4D-2). Rows carry a carton count, not the cartons. */
public interface ReturnReceiptSearch {

    /** Every filter optional; the caller's warehouses always narrow the result. */
    record Criteria(List<GoodsReceiptStatus> statuses, UUID warehouseId, UUID orderId, UUID shipmentId,
                    UUID returnRequestId, String number) {
    }

    record Row(UUID id, String number, GoodsReceiptStatus status, UUID warehouseId, UUID orderId, UUID shipmentId,
               UUID returnRequestId, Instant receivedAt, UUID receivedBy, Instant closedAt, int cartonCount) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /**
     * The DRAFT return receipts that list the carton, the newest first, within the caller's warehouses: where a carton
     * back from a customer waits before it is quarantined.
     */
    List<CartonReceipt> draftsListing(UUID stockPackageId);

    /** A return receipt as a carton's investigation names it. */
    record CartonReceipt(UUID id, String number, GoodsReceiptStatus status, UUID warehouseId,
                         UUID quarantineLocationId) {
    }
}
