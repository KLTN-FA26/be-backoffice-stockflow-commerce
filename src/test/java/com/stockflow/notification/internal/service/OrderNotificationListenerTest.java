package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.ShipmentStatusChanged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Who each customer message goes to. */
class OrderNotificationListenerTest {

    private final NotificationSender sender = mock(NotificationSender.class);
    private final OrderNotificationListener listener = new OrderNotificationListener(sender);

    @Test
    @DisplayName("regression (review of G4-2a): a guest order's confirmation goes to the contact e-mail it carries")
    void guestConfirmationHasARecipient() {
        listener.on(new OrderPlaced(UUID.randomUUID(), null, "guest@example.com", "SO-1", List.of(), BigDecimal.TEN,
                "VND"));
        verify(sender).send(eq(null), eq("guest@example.com"), eq("order.placed"), anyString(), anyString());
    }

    @Test
    @DisplayName("09 §4.1 step 3: handed over - the customer is told the carrier, no tracking number; READY says nothing")
    void handedOverTellsTheCustomer() {
        UUID customer = UUID.randomUUID();
        listener.on(changed(customer, "READY_TO_DISPATCH"));
        verifyNoInteractions(sender);
        listener.on(changed(customer, "HANDED_OVER"));
        verify(sender).send(eq(customer), eq("shipment.dispatched"), anyString(), contains("Giao Hàng Nhanh"));
    }

    private static ShipmentStatusChanged changed(UUID customer, String status) {
        return new ShipmentStatusChanged(UUID.randomUUID(), UUID.randomUUID(), "SO-1-S1", UUID.randomUUID(), "SO-1",
                customer, UUID.randomUUID(), "PARCEL", null, status, null, 1, "Giao Hàng Nhanh", "LD-1",
                UUID.randomUUID(), Instant.now());
    }
}
