package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who hears of a held production order and what they read: Sales, purchasing or the Warehouse Manager, every
 * active account of the role, by e-mail. The mail transport is a mock: nothing leaves the test.
 */
class ProductionNotificationListenerTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ProductionNotificationListener listener = new ProductionNotificationListener(
            new NotificationSender(mail, mock(RestClientFactory.class), "noreply@stockflow.local", ""),
            new StaffDirectory(identity));

    private static ProductionOrderHeld held(String reason) {
        return new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0001", reason, null, UUID.randomUUID(), null,
                "SOFA-3S-GREY", null);
    }

    private static StaffUser user(String email, String status) {
        return new StaffUser(UUID.randomUUID(), email, email, "Staff", status, List.of(), List.of(), false, null, null,
                Instant.EPOCH, "admin", 0);
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

    @Test
    void aRejectedFileIsMailedToEveryActiveSalesAccount() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"), user("sale2@example.com", "ACTIVE"),
                user("left@example.com", "DISABLED"), user(" ", "ACTIVE"));
        listener.on(held("FILE_REJECTED"));
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sale1@example.com", "sale2@example.com");
        assertThat(message.getFrom()).isEqualTo("noreply@stockflow.local");
        assertThat(message.getSubject()).contains("LSX-20261012-0001", "file in không đạt");
        assertThat(message.getText()).contains("liên hệ khách hàng", "đơn bán");
    }

    @Test
    void theWarehouseManagerNoticeGoesToTheManagersWhoCanSeeTheWarehouse() {
        UUID warehouse = UUID.randomUUID();
        when(identity.notificationRecipients(java.util.Set.of("WAREHOUSE_MANAGER"), warehouse)).thenReturn(List.of(
                new com.stockflow.identity.api.StaffContact(UUID.randomUUID(), "hcm-manager@example.com"),
                new com.stockflow.identity.api.StaffContact(UUID.randomUUID(), " ")));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0003", "MACHINE_DOWN", null,
                UUID.randomUUID(), warehouse, "SOFA-3S-GREY", null));
        assertThat(sent().getTo()).containsExactly("hcm-manager@example.com");
        org.mockito.Mockito.verify(identity, org.mockito.Mockito.never()).listUsers(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void waitingForTheCustomerAlsoGoesToSales() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0002", "WAITING_CUSTOMER",
                UUID.randomUUID(), null, null, "SOFA-3S-GREY", null));
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sale1@example.com");
        assertThat(message.getText()).contains("chờ khách hàng", "yêu cầu mẫu").doesNotContain("Ghi chú");
    }

    @Test
    void blanksShortAlertPurchasing() {
        staff(Role.PROCUREMENT_STAFF, user("buyer@example.com", "ACTIVE"));
        listener.on(new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0001", "BLANKS_SHORT", null,
                UUID.randomUUID(), null, "SOFA-3S-GREY", 3));
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
    void nobodyToWriteToSendsNothing() {
        staff(Role.SALES_STAFF);
        listener.on(held("FILE_REJECTED"));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aRefusingMailServerFailsTheListenerSoTheEventIsRetried() {
        staff(Role.SALES_STAFF, user("sale1@example.com", "ACTIVE"));
        doThrow(new org.springframework.mail.MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> listener.on(held("FILE_REJECTED")))
                .isInstanceOf(org.springframework.mail.MailException.class);
    }
}
