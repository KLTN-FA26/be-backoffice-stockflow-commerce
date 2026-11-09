package com.stockflow.notification.internal.service;

import com.stockflow.common.security.Role;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 00 §6 "Đơn sản xuất được gửi về kho": INFO to the warehouse manager(s) of the receiving warehouse - who can see that
 * warehouse when it is delivered - with the cartons, the volume and the need-by date, once each.
 */
class ProductionOrderArrivalNotificationListenerTest {

    private static final UUID WAREHOUSE = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("0192f000-0000-7000-8000-0000000000a2");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final ProductionOrderArrivalNotificationListener listener =
            new ProductionOrderArrivalNotificationListener(staff, inbox, warehouses);

    private static ProductionOrderSent sent(BigDecimal volume) {
        return new ProductionOrderSent(UUID.randomUUID(), ORDER, "PRD-20261110-0003", WAREHOUSE,
                LocalDate.of(2026, 11, 30), 24, volume, Instant.parse("2026-11-10T03:00:00Z"));
    }

    @Test
    @DisplayName("the receiving warehouse's managers are told, in-app, what is coming and when")
    @SuppressWarnings("unchecked")
    void managersOfTheWarehouse() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(staff.activeStaff(Role.WAREHOUSE_MANAGER, WAREHOUSE)).thenReturn(List.of(
                new StaffContact(first, "a@stockflow.vn"), new StaffContact(second, null)));
        when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(new WarehouseView(WAREHOUSE, "HCM",
                "Kho Hồ Chí Minh", "1 Lê Lợi", true)));
        when(inbox.post(any(), any())).thenReturn(2);

        listener.on(sent(new BigDecimal("2.016")));

        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactlyInAnyOrder(first, second);
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(message.getValue().category()).isEqualTo("PRODUCTION_ORDER");
        assertThat(message.getValue().title()).isEqualTo("Đơn sản xuất PRD-20261110-0003 sẽ về kho Kho Hồ Chí Minh");
        assertThat(message.getValue().body()).isEqualTo("24 thùng, thể tích 2.02 m³, cần hàng ngày 30/11/2026.");
        assertThat(message.getValue().link()).isEqualTo("/production/orders/" + ORDER);
        assertThat(message.getValue().dedupeKey()).as("a redelivered event tells nobody twice")
                .isEqualTo("production-order-sent:" + ORDER);
    }

    @Test
    @DisplayName("a carton size not recorded: the volume is said to be unknown, not zero")
    void volumeUnknown() {
        assertThat(ProductionOrderArrivalNotificationListener.message(sent(null), "Kho HCM").body())
                .isEqualTo("24 thùng, thể tích chưa rõ, cần hàng ngày 30/11/2026.");
    }

    @Test
    @DisplayName("nobody manages the warehouse: nothing is posted")
    void nobody() {
        when(staff.activeStaff(Role.WAREHOUSE_MANAGER, WAREHOUSE)).thenReturn(List.of());

        listener.on(sent(BigDecimal.ONE));

        verifyNoInteractions(inbox);
    }
}
