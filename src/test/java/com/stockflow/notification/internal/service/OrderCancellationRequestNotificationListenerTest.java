package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderCancellationRequested;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sales hear of a customer's cancellation request (WF-02 re-check round 3, B10; kltn-docs 17 §4.8 step 28): a
 * Vietnamese notice naming the order and the reason, opening the requests page, one per request.
 */
class OrderCancellationRequestNotificationListenerTest {

    @Test
    @DisplayName("the notice names the order and the reason in Vietnamese and opens the cancellation requests page")
    void notice() {
        UUID request = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        InboxMessage message = OrderCancellationRequestNotificationListener.message(new OrderCancellationRequested(
                request, order, "SO-20261120-000001", "CUSTOMER_REQUEST", Instant.parse("2026-11-20T03:00:00Z")));

        assertThat(message.title()).isEqualTo("Khách xin huỷ đơn SO-20261120-000001");
        assertThat(message.body()).contains("SO-20261120-000001").contains("Khách yêu cầu");
        assertThat(message.link()).isEqualTo("/admin/orders/cancellation-requests");
        assertThat(message.sourceId()).isEqualTo(order.toString());
        assertThat(message.dedupeKey()).isEqualTo("order-cancellation-request:" + request);
    }
}
