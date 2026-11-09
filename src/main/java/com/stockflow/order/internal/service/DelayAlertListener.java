package com.stockflow.order.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.VehicleHandedOver;
import com.stockflow.order.internal.domain.ProductionOrderSaved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * the operation that changed it. A recompute that fails is logged; the next operation or the night tries again.</p>
 */
@Component
class DelayAlertListener {

    private static final Logger log = LoggerFactory.getLogger(DelayAlertListener.class);

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

    private void recompute(UUID salesOrderId) {
        try {
            alerts.recompute(salesOrderId);
        } catch (RuntimeException failed) {
            log.warn("Delay alerts of order {} were not recomputed ({}); the nightly sweep tries again", salesOrderId,
                    failed.toString());
        }
    }
}
