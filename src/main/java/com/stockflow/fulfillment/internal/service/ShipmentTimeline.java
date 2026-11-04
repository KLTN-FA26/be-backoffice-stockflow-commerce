package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.ShipmentEventJpaEntity;
import com.stockflow.fulfillment.internal.repository.ShipmentEventJpaRepository;
import com.stockflow.order.api.OrderSummary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Writes what a shipment just went through: one {@code fulfillment.shipment_event} row per step (who, when, 09 BR-02)
 * and one {@link ShipmentStatusChanged} per step, published in the caller's transaction (the publication registry
 * delivers it after commit). Shared by packing (READY_TO_DISPATCH, CANCELLED) and dispatch (HANDED_OVER, outcomes), so
 * every transition is announced the same way.
 */
@Component
class ShipmentTimeline {

    private final ShipmentEventJpaRepository events;
    private final ApplicationEventPublisher publisher;

    ShipmentTimeline(ShipmentEventJpaRepository events, ApplicationEventPublisher publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    /**
     * @param before     the status the shipment had before these steps; null for one just made
     * @param order      its order, for the number and the customer's contact; null when the caller has none at hand
     *                   (the event then carries no contact)
     * @param orderNumber the order number when {@code order} is null
     */
    void record(Shipment shipment, ShipmentStatus before, OrderSummary order, String orderNumber, String carrierName,
                String loadCode) {
        List<Shipment.Step> steps = shipment.pullRecorded();
        ShipmentStatus previous = before;
        for (Shipment.Step step : steps) {
            events.save(new ShipmentEventJpaEntity(Identifiers.newId(), shipment.id(), step.status().name(),
                    step.at(), step.note(), step.by()));
            publisher.publishEvent(new ShipmentStatusChanged(Identifiers.newId(), shipment.id(), shipment.code(),
                    shipment.orderId(), order != null ? order.orderNumber() : orderNumber,
                    order == null ? null : order.customerId(),
                    shipment.warehouseId(), shipment.deliveryMode().name(),
                    previous == null ? null : previous.name(), step.status().name(),
                    step.status() == ShipmentStatus.DELIVERY_FAILED && shipment.failureReason() != null
                            ? shipment.failureReason().name() : null,
                    shipment.packageCount(), carrierName, loadCode, step.by(), step.at()));
            previous = step.status();
        }
    }
}
