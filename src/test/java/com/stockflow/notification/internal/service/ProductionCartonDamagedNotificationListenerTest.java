package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ProductionCartonWrittenOffDamaged;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ShortSalesOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** kltn-docs 20 §4.8 step 3, 17 §4.13 step 51: who is told of a damaged carton of a production order, and what they read. */
class ProductionCartonDamagedNotificationListenerTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final ProductionOrderDirectory productionOrders = mock(ProductionOrderDirectory.class);
    private final ProductionCartonDamagedNotificationListener listener =
            new ProductionCartonDamagedNotificationListener(new StaffDirectory(identity), inbox, productionOrders);

    private final UUID warehouse = UUID.randomUUID();
    private final ProductionCartonWrittenOffDamaged event = new ProductionCartonWrittenOffDamaged(UUID.randomUUID(),
            UUID.randomUUID(), "ADJ-0001", UUID.randomUUID(), "LPN-0001", "MUG-RED", "PO-2026-0007", UUID.randomUUID(),
            warehouse, UUID.randomUUID(), Instant.parse("2026-11-18T03:00:00Z"));

    @Test
    @DisplayName("the holders of production-orders:CREATE are told, once per adjustment, with the carton, SKU, lot and "
            + "the sales order left short")
    void theProductionCoordinatorIsToldWithTheShortOrder() {
        UUID coordinator = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("production-orders:CREATE", warehouse))
                .thenReturn(List.of(new StaffContact(coordinator, "pc@example.com")));
        when(productionOrders.shortSalesOrder(event.productionOrderId()))
                .thenReturn(Optional.of(new ShortSalesOrder("SO-0042", 3)));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(coordinator)), message.capture());
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.getValue().body()).contains("LPN-0001", "MUG-RED", "PO-2026-0007", "SO-0042", "3");
        assertThat(message.getValue().title()).contains("PO-2026-0007");
        assertThat(message.getValue().link()).isEqualTo("/production/orders/" + event.productionOrderId());
        assertThat(message.getValue().dedupeKey()).isEqualTo("production-carton-damaged:" + event.adjustmentId());
    }

    @Test
    @DisplayName("no order short: still told - a surplus carton is a write-off only, the coordinator decides")
    void toldEvenWhenNoOrderIsShort() {
        UUID coordinator = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("production-orders:CREATE", warehouse))
                .thenReturn(List.of(new StaffContact(coordinator, null)));
        when(productionOrders.shortSalesOrder(event.productionOrderId())).thenReturn(Optional.empty());
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(coordinator)), message.capture());
        assertThat(message.getValue().body()).contains("LPN-0001").doesNotContain("SO-");
    }

    @Test
    @DisplayName("nobody to tell is logged, not thrown")
    void nobodyToTellIsNotAnError() {
        when(identity.notificationRecipientsHolding("production-orders:CREATE", warehouse)).thenReturn(List.of());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
    }
}
