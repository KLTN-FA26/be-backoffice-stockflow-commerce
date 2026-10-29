package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who hears of a held production order and what they read: Sales, purchasing or the Warehouse Manager. In-app first
 * (one inbox row per person per hold), then e-mail best effort to the people newly told; a refusing mail server never
 * fails the event. The inbox and the mail transport are mocks: nothing leaves the test.
 */
class ProductionNotificationListenerTest {

    private static final Instant HELD_AT = Instant.parse("2026-10-13T03:00:00Z");

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final ProductionNotificationListener listener = new ProductionNotificationListener(
            new NotificationSender(mail, mock(RestClientFactory.class), "noreply@stockflow.local", ""),
            new StaffDirectory(identity), new StaffNotices(inbox));

    @BeforeEach
    void everyoneIsNewlyTold() {
        when(inbox.post(any(), any())).thenReturn(1);
    }

    private static ProductionOrderHeld held(String reason) {
        return new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0001", reason, null, UUID.randomUUID(), null,
                "SOFA-3S-GREY", null, HELD_AT);
    }

    private static StaffUser user(String email, String status) {
        return new StaffUser(UUID.randomUUID(), email, email, "Staff", status, List.of(), List.of(), false, null, null,
                Instant.EPOCH, "admin", null, 0);
    }

    private void staff(Role role, StaffUser... users) {
        when(identity.listUsers(argThat((ListUsersQuery q) -> q != null && role.name().equals(q.roleCode())
                && "ACTIVE".equals(q.status()) && q.page() == 0)))
                .thenReturn(PageResponse.of(List.of(users), 0, 100, users.length));
    }

    private SimpleMailMessage sent() {
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        return message.getValue();
    }

    private InboxMessage posted() {
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, org.mockito.Mockito.atLeastOnce()).post(any(), message.capture());
        return message.getValue();
    }

    @Test
    void aRejectedFileReachesEveryActiveSalesAccountInAppAndByMail() {
        StaffUser noMail = user(" ", "ACTIVE");
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"), user("sale2@example.com", "ACTIVE"),
                user("left@example.com", "DISABLED"), noMail);
        ProductionOrderHeld event = held("FILE_REJECTED");
        listener.on(event);

        verify(inbox, times(3)).post(any(), any());
        InboxMessage message = posted();
        verify(inbox).post(List.of(noMail.userId()), message);
        assertThat(message.category()).isEqualTo("PRODUCTION_HOLD");
        assertThat(message.title()).contains("LSX-20261012-0001", "file in không đạt");
        assertThat(message.link()).isEqualTo("/admin/production/orders/" + event.productionOrderId());
        assertThat(message.dedupeKey()).isEqualTo("production-hold:%s:FILE_REJECTED:%s"
                .formatted(event.productionOrderId(), HELD_AT));
        SimpleMailMessage mailed = sent();
        assertThat(mailed.getTo()).as("the account with no e-mail is told in-app only")
                .containsExactly("sale1@example.com", "sale2@example.com");
        assertThat(mailed.getSubject()).contains("LSX-20261012-0001", "file in không đạt");
        assertThat(mailed.getText()).contains("liên hệ khách hàng", "đơn bán");
    }

    @Test
    void aRedeliveredEventTellsNobodyTwice() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"));
        when(inbox.post(any(), any())).thenReturn(0);   // they already have this hold's row
        listener.on(held("FILE_REJECTED"));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aRefusingMailServerDoesNotFailTheEventTheInAppNoticeStands() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"));
        doThrow(new org.springframework.mail.MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatCode(() -> listener.on(held("FILE_REJECTED"))).doesNotThrowAnyException();
        verify(inbox).post(any(), any());
    }

    @Test
    void theWarehouseManagerNoticeGoesToTheManagersWhoCanSeeTheWarehouse() {
        UUID warehouse = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipients(Set.of("WAREHOUSE_MANAGER"), warehouse)).thenReturn(List.of(
                new StaffContact(manager, "hcm-manager@example.com")));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0003", "MACHINE_DOWN", null,
                UUID.randomUUID(), warehouse, "SOFA-3S-GREY", null, HELD_AT));
        InboxMessage message = posted();
        verify(inbox).post(List.of(manager), message);
        assertThat(sent().getTo()).containsExactly("hcm-manager@example.com");
        verify(identity, never()).listUsers(any());
    }

    @Test
    void waitingForTheCustomerAlsoGoesToSales() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0002", "WAITING_CUSTOMER",
                UUID.randomUUID(), null, null, "SOFA-3S-GREY", null, HELD_AT));
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sale1@example.com");
        assertThat(message.getText()).contains("chờ khách hàng", "yêu cầu mẫu");
    }

    @Test
    void blanksShortAlertPurchasingWithTheCount() {
        staff(Role.PROCUREMENT_STAFF, user("buyer@example.com", "ACTIVE"));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0001", "BLANKS_SHORT", null,
                UUID.randomUUID(), null, "SOFA-3S-GREY", 3, HELD_AT));
        assertThat(posted().body()).isEqualTo("Cần thêm 3 phôi SOFA-3S-GREY.");
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("buyer@example.com");
        assertThat(message.getText()).contains("thiếu phôi", "Cần thêm 3 phôi SOFA-3S-GREY", "bổ sung phôi");
    }

    @Test
    void theRestGoesToTheWarehouseManager() {
        staff(Role.WAREHOUSE_MANAGER, user("manager@example.com", "ACTIVE"));
        for (String reason : List.of("MACHINE_DOWN", "SUBCONTRACTOR_LATE", "OTHER")) {
            assertThat(ProductionNotificationListener.audience(reason)).isEqualTo(Role.WAREHOUSE_MANAGER);
        }
        listener.on(held("MACHINE_DOWN"));
        assertThat(sent().getTo()).containsExactly("manager@example.com");
    }

    @Test
    void everyPageOfTheRoleIsRead() {
        when(identity.listUsers(argThat((ListUsersQuery q) -> q != null && q.page() == 0)))
                .thenReturn(PageResponse.of(List.of(user("a@example.com", "ACTIVE")), 0, 1, 2));
        when(identity.listUsers(argThat((ListUsersQuery q) -> q != null && q.page() == 1)))
                .thenReturn(PageResponse.of(List.of(user("b@example.com", "ACTIVE")), 1, 1, 2));
        listener.on(held("FILE_REJECTED"));
        assertThat(sent().getTo()).containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void nobodyToTellPostsAndSendsNothing() {
        staff(Role.SALES_STAFF);
        listener.on(held("FILE_REJECTED"));
        verify(inbox, never()).post(any(Collection.class), any());
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
