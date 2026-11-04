package com.stockflow.notification.internal.service;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who is told, how often, and what the e-mail flag does: off sends nothing, on sends to the newly told only. */
class InventoryAlertNotificationListenerTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final StaffContact PLANNER = new StaffContact(UUID.randomUUID(), "planner@example.com");
    private static final StaffContact MANAGER = new StaffContact(UUID.randomUUID(), "manager@example.com");

    private Inbox inbox;
    private IdentityService identities;
    private WarehouseService warehouses;
    private NotificationSender sender;

    @BeforeEach
    void setUp() {
        inbox = mock(Inbox.class);
        identities = mock(IdentityService.class);
        warehouses = mock(WarehouseService.class);
        sender = mock(NotificationSender.class);
        when(warehouses.findWarehouse(HCM)).thenReturn(Optional.of(new WarehouseView(HCM, "HCM", "Ho Chi Minh", "addr", true)));
        when(inbox.post(any(), any())).thenReturn(1);
    }

    private InventoryAlertNotificationListener listener(boolean email) {
        return new InventoryAlertNotificationListener(inbox, identities, warehouses, sender, email,
                "INVENTORY_PLANNER,SYSTEM_ADMIN", "WAREHOUSE_MANAGER, INVENTORY_PLANNER ,SYSTEM_ADMIN", "vi");
    }

    private static InventoryAlertsRaised reorder() {
        return new InventoryAlertsRaised(UUID.randomUUID(), "REORDER", null,
                List.of(new InventoryAlertsRaised.Item(UUID.randomUUID(), "A", null, null, 2, 10, false)), Instant.now());
    }

    private static InventoryAlertsRaised expiring() {
        return new InventoryAlertsRaised(UUID.randomUUID(), "EXPIRING", HCM, List.of(new InventoryAlertsRaised.Item(
                UUID.randomUUID(), "A", "L1", LocalDate.now().plusDays(3), 2, null, false)), Instant.now());
    }

    @Test
    void stockLevelAlertsGoToThePlannerAndAdminSystemWideNotToWarehouseRoles() {
        when(identities.notificationRecipients(Set.of("INVENTORY_PLANNER", "SYSTEM_ADMIN"), null)).thenReturn(List.of(PLANNER));
        listener(false).on(reorder());
        verify(identities).notificationRecipients(Set.of("INVENTORY_PLANNER", "SYSTEM_ADMIN"), null);
        verify(inbox).post(eq(List.of(PLANNER.userId())), any(InboxMessage.class));
    }

    @Test
    void expiryAlertsGoToTheWarehouseManagerOfThatWarehouseAndTheRolesThatSeeEverything() {
        when(identities.notificationRecipients(Set.of("WAREHOUSE_MANAGER", "INVENTORY_PLANNER", "SYSTEM_ADMIN"), HCM))
                .thenReturn(List.of(MANAGER, PLANNER));
        listener(false).on(expiring());
        verify(identities).notificationRecipients(Set.of("WAREHOUSE_MANAGER", "INVENTORY_PLANNER", "SYSTEM_ADMIN"), HCM);
        verify(inbox).post(eq(List.of(MANAGER.userId())), any(InboxMessage.class));
        verify(inbox).post(eq(List.of(PLANNER.userId())), any(InboxMessage.class));
    }

    @Test
    void everyRecipientGetsTheSameMessageWithTheWarehouseNameAndTheDailyKey() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(MANAGER, PLANNER));
        listener(false).on(expiring());
        ArgumentCaptor<InboxMessage> messages = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(2)).post(any(), messages.capture());
        assertThat(messages.getAllValues().get(0)).isEqualTo(messages.getAllValues().get(1));
        assertThat(messages.getValue().title()).contains("HCM");
        assertThat(messages.getValue().dedupeKey()).startsWith("EXPIRING:" + HCM + ":");
    }

    @Test
    void noRecipientIsNotAnErrorAndPostsNothing() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of());
        assertThatCode(() -> listener(true).on(reorder())).doesNotThrowAnyException();
        verify(inbox, never()).post(any(), any());
        verify(sender, never()).sendInventoryAlertEmail(anyString(), anyString(), anyString());
    }

    @Test
    void withTheFlagOffNoEmailIsSentAndNothingBreaks() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(PLANNER));
        listener(false).on(reorder());
        verify(inbox).post(any(), any());
        verify(sender, never()).sendInventoryAlertEmail(anyString(), anyString(), anyString());
    }

    @Test
    void withTheFlagOnTheNewlyToldGetTheSameTextByEmail() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(PLANNER, MANAGER));
        listener(true).on(reorder());
        ArgumentCaptor<InboxMessage> posted = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(2)).post(any(), posted.capture());
        verify(sender).sendInventoryAlertEmail("planner@example.com", posted.getValue().title(), posted.getValue().body());
        verify(sender).sendInventoryAlertEmail("manager@example.com", posted.getValue().title(), posted.getValue().body());
    }

    @Test
    void someoneWhoAlreadyHadTheMessageTodayIsNotMailedAgain() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(PLANNER, MANAGER));
        when(inbox.post(eq(List.of(PLANNER.userId())), any())).thenReturn(0);
        when(inbox.post(eq(List.of(MANAGER.userId())), any())).thenReturn(1);
        listener(true).on(reorder());
        verify(sender, never()).sendInventoryAlertEmail(eq("planner@example.com"), anyString(), anyString());
        verify(sender).sendInventoryAlertEmail(eq("manager@example.com"), anyString(), anyString());
    }

    @Test
    void aMailServerThatFailsNeverFailsTheEventOrTheOtherRecipients() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(PLANNER, MANAGER));
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(sender)
                .sendInventoryAlertEmail(eq("planner@example.com"), anyString(), anyString());
        assertThatCode(() -> listener(true).on(reorder())).doesNotThrowAnyException();
        verify(inbox, times(2)).post(any(), any());
        verify(sender).sendInventoryAlertEmail(eq("manager@example.com"), anyString(), anyString());
    }

    @Test
    void aRecipientWithoutAnAddressGetsTheInboxMessageOnly() {
        when(identities.notificationRecipients(any(), any())).thenReturn(List.of(new StaffContact(UUID.randomUUID(), " ")));
        listener(true).on(reorder());
        verify(inbox).post(any(), any());
        verify(sender, never()).sendInventoryAlertEmail(anyString(), anyString(), anyString());
    }
}
