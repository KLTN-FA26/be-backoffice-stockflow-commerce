package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Read side of transfer orders for the list screen. Rows carry no lines. */
public interface TransferOrderSearch {

    record Criteria(List<TransferStatus> statuses, UUID fromWarehouseId, UUID toWarehouseId, String number) {
    }

    record Row(UUID id, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferStatus status,
               java.time.LocalDate expectedDate, java.time.Instant createdAt, java.time.Instant dispatchedAt) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);
}
