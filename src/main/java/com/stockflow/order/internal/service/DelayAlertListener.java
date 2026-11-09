package com.stockflow.order.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.VehicleHandedOver;
import com.stockflow.order.internal.domain.ProductionOrderSaved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Recomputes a sales order's delay alerts after an operation that may change them committed (4A-7, kltn-docs 17 §4.12
 * step 48: "tính lại khi dữ liệu liên quan đổi - lập / gửi / nhận Đơn sản xuất, ghi ngày hoàn thành dự kiến, dời cửa
 * sổ giao, bàn giao"):
 * <ul>
 *   <li>a production order written - drawn up, edited, submitted, sent, accepted with its expected day, received
 *       against, cancelled, closed ({@link ProductionOrderSaved}, published on every save);</li>
 *   <li>goods of the order handed over ({@link VehicleHandedOver}), the order released (its shortage is no longer
 *       followed), cancelled, or its remainder closed.</li>
 * </ul>
 * Moving a batch's window (BR-28) is not built yet; when it is, its event belongs here. Stock arriving and being held
 * (a putaway, a hold released) is left to the nightly sweep: it says nothing about which order it serves.
 *
 * <p>After the commit and asynchronous ({@code @ApplicationModuleListener}): an alert never holds up, and never fails,
 * the operation that changed it. A recompute that fails is thrown, so its publication stays incomplete in the event
 * registry and is retried; the nightly sweep is the last net.</p>
 */
@Component
class DelayAlertListener {

    private final DelayAlertService alerts;

    DelayAlertListener(DelayAlertService alerts) {
        this.alerts = alerts;
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSaved event) {
        recompute(event.salesOrderId());
    }

    @ApplicationModuleListener
    public void on(VehicleHandedOver event) {
        recompute(event.orderId());
    }

    @ApplicationModuleListener
    public void on(OrderReleased event) {
        recompute(event.orderId());
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        recompute(event.orderId());
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        recompute(event.orderId());
    }

    /**
     * Not caught: a recompute that throws leaves its event's publication incomplete in the registry, to be resubmitted,
     * instead of marking it done and losing the trigger until the night. After the commit and asynchronous, the failure
     * reaches nobody's operation.
     */
    private void recompute(UUID salesOrderId) {
        alerts.recompute(salesOrderId);
    }
}
