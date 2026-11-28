package com.stockflow.notification.internal.service;

import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sales hear of a quote request on a design (kltn-docs 12 §4.8), and of the decision on a design one is open on (12
 * §4.5 step 17, B40) - in Vietnamese, by SKU, once per request or decision - and since 2026-11-28 only whoever looks
 * after the customer hears: the owner or the cover, else the Sales Managers marked "cần phân công" (14 §4.1 step 1,
 * 00 §6); never every holder of the right.
 */
class DesignQuoteRequestNotificationListenerTest {

    private final NotificationSender sender = mock(NotificationSender.class);
    private final SalesNotices sales = mock(SalesNotices.class);
    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final StaffNotices notices = mock(StaffNotices.class);
    private final DesignQuoteRequestNotificationListener listener =
            new DesignQuoteRequestNotificationListener(sender, sales, notices);

    {
        when(sales.audiences()).thenReturn(audiences);
    }

    private static DesignQuoteRequested requested(String status, String customerSku, LocalDate date) {
        return new DesignQuoteRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, "CUP-12",
                customerSku, 20, date, Instant.parse("2026-11-08T03:00:00Z"));
    }

    @Test
    void aRequestOnAnApprovedDesignNamesItsOwnSkuAndCanBeQuotedNow() {
        var event = requested("APPROVED", "CUP12-0312345678-01", LocalDate.of(2026, 12, 15));
        InboxMessage message = DesignQuoteRequestNotificationListener.message(event);

        assertThat(message.title()).contains("CUP12-0312345678-01");
        assertThat(message.body()).contains("CUP12-0312345678-01", "20 thùng", "Có thể lập báo giá", "15/12/2026");
        assertThat(message.severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(message.dedupeKey()).isEqualTo("design-quote:" + event.requestId() + ":"
                + event.requestedAt().toEpochMilli());
    }

    @Test
    void anApprovedDesignIsQuotableEvenWithoutItsOwnSkuAtHand() {
        InboxMessage message = DesignQuoteRequestNotificationListener.message(requested("APPROVED", null, null));

        assertThat(message.body()).contains("CUP-12", "Có thể lập báo giá").doesNotContain("chờ duyệt");
    }

    @Test
    void aRequestOnADesignWaitingForReviewSaysToWaitForTheApproval() {
        InboxMessage message = DesignQuoteRequestNotificationListener.message(requested("PENDING_REVIEW", null, null));

        assertThat(message.title()).contains("CUP-12");
        assertThat(message.body()).contains("CUP-12", "chờ duyệt").doesNotContain("Ngày giao");
    }

    @Test
    void theSalesStaffHearOfADecisionOnlyWhenARequestIsOpen() {
        UUID request = UUID.randomUUID();
        var approved = new DesignReviewed(UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "CUP12-0312345678-01",
                Instant.parse("2026-11-08T03:00:00Z"), request);
        InboxMessage message = DesignQuoteRequestNotificationListener.message(approved);
        assertThat(message.body()).contains("CUP12-0312345678-01", "có thể lập báo giá");
        assertThat(message.dedupeKey()).isEqualTo("design-quote-decision:" + request + ":"
                + approved.decidedAt().toEpochMilli());
        assertThat(DesignQuoteRequestNotificationListener.message(new DesignReviewed(UUID.randomUUID(),
                UUID.randomUUID(), "RETURNED", "CUP-12", Instant.now(), request)).severity())
                .isEqualTo(InboxSeverity.WARN);

        listener.on(new DesignReviewed(UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "X", Instant.now(), null));
        verify(audiences, never()).ofCustomerAccount(any());
        verify(notices, never()).deliver(any(), any(), any());
    }

    @Test
    @DisplayName("14 §4.1 step 1: a request of an owned customer goes to its owner only, unmarked")
    void theOwnerIsTold() {
        var request = requested("APPROVED", "CUP12-0312345678-01", null);
        var owner = new StaffContact(UUID.randomUUID(), "owner@example.test");
        SalesAudience audience = new SalesAudience(List.of(owner.userId()), false);
        when(audiences.ofCustomer(request.customerId())).thenReturn(java.util.Optional.of(audience));
        when(sales.contacts(audience)).thenReturn(List.of(owner));

        listener.on(request);

        verify(notices).deliver(eq(List.of(owner)), argThat((InboxMessage m) -> !m.body().contains("Cần phân công")),
                any());
    }

    @Test
    @DisplayName("§10.2: a request of a customer with no owner reaches the Sales Managers only, marked 'cần phân công'")
    void anUnownedCustomersRequestGoesToTheManagersMarked() {
        var request = requested("APPROVED", "CUP12-0312345678-01", null);
        var manager = new StaffContact(UUID.randomUUID(), "manager@example.test");
        SalesAudience audience = new SalesAudience(List.of(manager.userId()), true);
        when(audiences.ofCustomer(request.customerId())).thenReturn(java.util.Optional.of(audience));
        when(sales.contacts(audience)).thenReturn(List.of(manager));

        listener.on(request);

        verify(notices).deliver(eq(List.of(manager)), argThat((InboxMessage m) -> m.body().contains("Cần phân công")),
                any());
    }

    @Test
    @DisplayName("The decision on a design follows the customer of the design's owner login")
    void aDecisionReachesTheOwnerOfTheDesignsCustomer() {
        UUID login = UUID.randomUUID();
        var owner = new StaffContact(UUID.randomUUID(), "owner@example.test");
        SalesAudience audience = new SalesAudience(List.of(owner.userId()), false);
        when(audiences.ofCustomerAccount(login)).thenReturn(java.util.Optional.of(audience));
        when(sales.contacts(audience)).thenReturn(List.of(owner));

        listener.on(new DesignReviewed(UUID.randomUUID(), login, "APPROVED", "CUP12", Instant.now(), UUID.randomUUID()));

        verify(notices).deliver(eq(List.of(owner)), any(InboxMessage.class), any());
    }

    @Test
    void nobodyToTellTellsNobody() {
        when(audiences.ofCustomer(any())).thenReturn(java.util.Optional.empty());

        listener.on(requested("APPROVED", "CUP12-0312345678-01", null));

        verify(notices, never()).deliver(any(), any(), any());
    }
}
