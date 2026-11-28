package com.stockflow.order.internal.service;

import com.stockflow.order.api.CartonRemovals;
import com.stockflow.order.internal.repository.OrderHistoryNotes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** See {@link CartonRemovals}. Appends to {@code ordering.order_carton_removal} in the check station's transaction. */
@Service
@Transactional
class CartonRemovalHistory implements CartonRemovals {

    private final OrderHistoryNotes notes;

    CartonRemovalHistory(OrderHistoryNotes notes) {
        this.notes = notes;
    }

    @Override
    public void record(UUID orderId, UUID packageId, String lpn, String reason, String note,
                       boolean continuesPartial, UUID by, Instant at) {
        notes.cartonRemoved(orderId, packageId, lpn, reason, note, continuesPartial, by, at);
    }
}
