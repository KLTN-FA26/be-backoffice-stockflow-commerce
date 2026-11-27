package com.stockflow.order.internal.service;

import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.order.api.DeliverySchedules;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * {@link DeliverySchedules} as the customer's own schedule: {@link DeliveryBatchService#scheduleOfCustomer}, the read
 * behind the portal's page, so another module is told no more of a schedule than its customer is.
 *
 * <p>An order that is not the customer's reads as an empty schedule rather than a refusal, and is found so
 * <i>before</i> the schedule is read: the schedule's own refusal, thrown through its transactional proxy inside this
 * read, would mark the caller's transaction rollback-only even if caught here.</p>
 */
@Component
class DeliverySchedulesAdapter implements DeliverySchedules {

    private final DeliveryBatchService batches;
    private final CustomerDocuments documents;

    DeliverySchedulesAdapter(DeliveryBatchService batches, CustomerDocuments documents) {
        this.batches = batches;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> customerSchedule(UUID customerId, UUID orderId) {
        if (customerId == null || orderId == null || documents.order(orderId)
                .filter(order -> customerId.equals(order.customerId())).isEmpty()) {
            return List.of();
        }
        return batches.scheduleOfCustomer(customerId, orderId).batches().stream()
                .map(batch -> new Batch(batch.seq(), batch.windowStart(), batch.windowEnd(),
                        batch.state() == null ? null : batch.state().name(), batch.late()))
                .toList();
    }
}
