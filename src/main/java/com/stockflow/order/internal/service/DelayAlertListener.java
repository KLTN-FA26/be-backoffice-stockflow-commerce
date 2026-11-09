package com.stockflow.order.internal.service;

import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.order.internal.domain.ContractOrderSaved;
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
 *   <li>an order of a contract written ({@link ContractOrderSaved}, published on every save): confirmed by its
 *       signing, its installment on signing or its credit approval; its shipping warehouse chosen and stock held there;
 *       stock arrived and topped up for it (a putaway tops up the waiting orders, which saves them); released, handed
 *       over, put on hold, cancelled, its remainder closed;</li>
 *   <li>a hold of the order released ({@link StockReservationReleased}): inventory frees it without writing the order,
 *       and what it no longer holds may be short again.</li>
 * </ul>
 * Moving a batch's window (BR-28) is not built yet; when it is, its event belongs here.
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
    public void on(ContractOrderSaved event) {
        recompute(event.salesOrderId());
    }

    @ApplicationModuleListener
    public void on(StockReservationReleased event) {
        if (event.orderId() != null) {
            recompute(event.orderId());
        }
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
