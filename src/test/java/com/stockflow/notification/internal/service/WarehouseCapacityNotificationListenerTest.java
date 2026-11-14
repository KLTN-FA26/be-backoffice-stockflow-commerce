package com.stockflow.notification.internal.service;

import com.stockflow.common.security.Role;
import com.stockflow.contracts.WarehouseCapacityTurnedNegative;
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
 * 00 §6 "Còn trống dự kiến của kho chuyển sang âm" (06 §4.6 step 8, C42): WARN to the warehouse managers of that
 * warehouse - and nobody else - with the production order and the shortfall, once per production order.
 */
class WarehouseCapacityNotificationListenerTest {

    private static final UUID WAREHOUSE = UUID.fromString("0192f000-0000-7000-8000-0000000000b1");
    private static final UUID PRODUCTION_ORDER = UUID.fromString("0192f000-0000-7000-8000-0000000000b2");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final WarehouseCapacityNotificationListener listener =
            new WarehouseCapacityNotificationListener(staff, inbox, warehouses);

    private static WarehouseCapacityTurnedNegative event() {
        return new WarehouseCapacityTurnedNegative(UUID.randomUUID(), WAREHOUSE, PRODUCTION_ORDER, "PRD-20261125-0001",
                new BigDecimal("0.168"), Instant.parse("2026-11-25T03:00:00Z"));
    }

    @Test
    @DisplayName("the warehouse's managers are told, WARN, with the production order and how far below zero")
    @SuppressWarnings("unchecked")
    void managersOfTheWarehouse() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(staff.activeStaff(Role.WAREHOUSE_MANAGER, WAREHOUSE)).thenReturn(List.of(
                new StaffContact(first, "a@stockflow.vn"), new StaffContact(second, null),
                new StaffContact(first, "a@stockflow.vn")));
        when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(new WarehouseView(WAREHOUSE, "HCM",
                "Kho Hồ Chí Minh", "1 Lê Lợi", true)));
        when(inbox.post(any(), any())).thenReturn(2);

        listener.on(event());

        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactly(first, second);
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.getValue().category()).isEqualTo("WAREHOUSE_CAPACITY");
        assertThat(message.getValue().title()).isEqualTo("Kho Kho Hồ Chí Minh sẽ thiếu chỗ");
        assertThat(message.getValue().body()).contains("PRD-20261125-0001", "Kho Hồ Chí Minh", "âm 0.17 m³");
        assertThat(message.getValue().link()).isEqualTo("/warehouse/capacity/" + WAREHOUSE);
        assertThat(message.getValue().sourceId()).isEqualTo(WAREHOUSE.toString());
        assertThat(message.getValue().dedupeKey()).as("a redelivered event tells nobody twice")
                .isEqualTo("capacity-negative:" + WAREHOUSE + ":" + PRODUCTION_ORDER);
    }

    @Test
    @DisplayName("nobody manages the warehouse: nothing is posted")
    void nobody() {
        when(staff.activeStaff(Role.WAREHOUSE_MANAGER, WAREHOUSE)).thenReturn(List.of());

        listener.on(event());

        verifyNoInteractions(inbox);
    }
}
