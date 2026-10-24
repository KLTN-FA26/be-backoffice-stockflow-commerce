package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.StockDamageReported;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who hears of a damage report and what they read: the warehouse's managers always (they decide), the coordinators
 * when an order is on hold for it, the planners when reservations now exceed free stock. The inbox and the mail
 * transport are mocks: nothing leaves the test.
 */
class StockDamageNotificationListenerTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final StockDamageNotificationListener listener = new StockDamageNotificationListener(
            new NotificationSender(new SmtpEmailTransport(mail), mock(RestClientFactory.class), "noreply@stockflow.local", ""),
            new StaffDirectory(identity), new StaffNotices(inbox));

    private final UUID manager = UUID.randomUUID();
    private final UUID coordinator = UUID.randomUUID();
    private final UUID planner = UUID.randomUUID();

    @BeforeEach
    void staff() {
        when(inbox.post(any(), any())).thenReturn(1);
        when(identity.notificationRecipients(Set.of("WAREHOUSE_MANAGER"), WAREHOUSE))
                .thenReturn(List.of(new StaffContact(manager, "manager@example.com")));
        when(identity.notificationRecipients(Set.of("ORDER_COORDINATOR"), WAREHOUSE))
                .thenReturn(List.of(new StaffContact(coordinator, "coordinator@example.com")));
        when(identity.notificationRecipients(Set.of("INVENTORY_PLANNER"), WAREHOUSE))
                .thenReturn(List.of(new StaffContact(planner, null)));
    }

    private static StockDamageReported event(String origin, UUID order, boolean onHold, int shortfall) {
        return new StockDamageReported(UUID.randomUUID(), "DMG-20261025-0001", origin, WAREHOUSE, "CUP-PP-500", "L1",
                "HCM-A01-1-A", "HCM-QC01", 3, "BROKEN", order, onHold, shortfall);
    }

    private InboxMessage postedTo(UUID user) {
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(user)), message.capture());
        return message.getValue();
    }

    @Test
    void aShelfReportAsksTheWarehouseManagersToDecideAndNobodyElse() {
        StockDamageReported event = event("STOCK_CHECK", null, false, 0);
        listener.on(event);

        InboxMessage message = postedTo(manager);
        assertThat(message.category()).isEqualTo("STOCK_DAMAGE");
        assertThat(message.title()).contains("DMG-20261025-0001", "3 x CUP-PP-500", "chờ quyết định");
        assertThat(message.body()).contains("lô L1", "vỡ", "khi kiểm kệ", "HCM-A01-1-A", "HCM-QC01",
                "huỷ, trả nhà cung cấp hoặc khôi phục");
        assertThat(message.link()).isEqualTo("/admin/inventory/damage-reports/" + event.reportId());
        assertThat(message.dedupeKey()).isEqualTo("stock-damage:%s:WAREHOUSE_MANAGER".formatted(event.reportId()));
        verify(inbox, times(1)).post(any(), any());
        var mailed = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(mailed.capture());
        assertThat(mailed.getValue().getTo()).containsExactly("manager@example.com");
        assertThat(mailed.getValue().getText()).contains("/admin/inventory/damage-reports/" + event.reportId());
    }

    @Test
    void anOrderOnHoldTellsTheCoordinatorsAndAShortfallThePlanners() {
        UUID order = UUID.randomUUID();
        StockDamageReported event = event("PACKING", order, true, 2);
        listener.on(event);

        postedTo(manager);
        InboxMessage held = postedTo(coordinator);
        assertThat(held.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(held.link()).isEqualTo("/admin/orders/" + order);
        assertThat(held.body()).contains("khi đóng gói", "phân bổ lại");
        InboxMessage shortfall = postedTo(planner);
        assertThat(shortfall.title()).contains("CUP-PP-500", "2");
        assertThat(shortfall.dedupeKey()).as("one a day per SKU and warehouse, however many reports")
                .startsWith("stock-damage-shortfall:CUP-PP-500:" + WAREHOUSE + ":");
    }

    @Test
    void aRedeliveredEventMailsNobodyAndARefusingMailServerFailsNothing() {
        when(inbox.post(any(), any())).thenReturn(0);
        listener.on(event("PICKING", UUID.randomUUID(), true, 0));
        verify(mail, never()).send(any(SimpleMailMessage.class));

        when(inbox.post(any(), any())).thenReturn(1);
        doThrow(new org.springframework.mail.MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatCode(() -> listener.on(event("PICKING", UUID.randomUUID(), true, 0))).doesNotThrowAnyException();
    }

    @Test
    void nobodyToTellIsLoggedNotFailed() {
        when(identity.notificationRecipients(Set.of("WAREHOUSE_MANAGER"), WAREHOUSE)).thenReturn(List.of());
        assertThatCode(() -> listener.on(event("STOCK_CHECK", null, false, 0))).doesNotThrowAnyException();
        verify(inbox, never()).post(any(), any());
    }
}
