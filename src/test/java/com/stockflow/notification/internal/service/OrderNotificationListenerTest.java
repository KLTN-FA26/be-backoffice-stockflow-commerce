package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderDelivered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.cancelled;
import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static com.stockflow.notification.internal.service.EmailFixtures.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Which order events become a customer message, and for whom. */
class OrderNotificationListenerTest {

    private final CustomerEmailDelivery delivery = mock(CustomerEmailDelivery.class);
    private final OrderNotificationListener listener =
            new OrderNotificationListener(new CustomerEmailComposer(properties()), delivery);

    private CustomerMessage delivered() {
        var message = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(delivery).deliver(message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("regression (review of G4-2a): a guest order's confirmation goes to the contact e-mail it carries")
    void guestConfirmationHasARecipient() {
        listener.on(placed(null, "guest@cup-maker.vn"));
        CustomerMessage message = delivered();
        assertThat(message.templateCode()).isEqualTo("order.placed");
        assertThat(message.guestAddress()).isEqualTo("guest@cup-maker.vn");
    }

    @Test
    @DisplayName("an account's order is addressed by customer id, looked up when it is sent - not from the event")
    void accountConfirmationIsLookedUp() {
        UUID customer = UUID.randomUUID();
        listener.on(placed(customer, "contact@cup-maker.vn"));
        CustomerMessage message = delivered();
        assertThat(message.customerId()).isEqualTo(customer);
        assertThat(message.guestAddress()).isNull();
    }

    @Test
    @DisplayName("09 §4.1 step 3: handed over - the customer is told; READY_TO_DISPATCH says nothing")
    void handedOverTellsTheCustomer() {
        UUID customer = UUID.randomUUID();
        listener.on(shipment(customer, "READY_TO_DISPATCH", null, "Giao Hàng Nhanh"));
        verifyNoInteractions(delivery);
        listener.on(shipment(customer, "HANDED_OVER", null, "Giao Hàng Nhanh"));
        CustomerMessage message = delivered();
        assertThat(message.templateCode()).isEqualTo("shipment.handed-over");
        assertThat(EmailTemplates.text(message.draft().write("A"))).contains("Giao Hàng Nhanh");
    }

    @Test
    @DisplayName("a cancellation is told; an order delivered through shipments is not told twice")
    void cancellationAndDelivery() {
        UUID customer = UUID.randomUUID();
        listener.on(new OrderDelivered(UUID.randomUUID(), "SO-1", customer, "CREDIT", BigDecimal.TEN, BigDecimal.ZERO,
                "VND", 30, Instant.now(), UUID.randomUUID(), BigDecimal.TEN));
        verifyNoInteractions(delivery);
        listener.on(cancelled(customer, "CUSTOMER_REQUEST", BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(delivered().templateCode()).isEqualTo("order.cancelled");
    }
}
