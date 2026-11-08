package com.stockflow.notification.internal.service;

import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sales staff hear of a quote request on a design (kltn-docs 12 §4.8), and of the decision on a design one is open
 * on (12 §4.5 step 17, B40) - in Vietnamese, by SKU, once per request or decision.
 */
class DesignQuoteRequestNotificationListenerTest {

    private final NotificationSender sender = mock(NotificationSender.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final StaffNotices notices = mock(StaffNotices.class);
    private final DesignQuoteRequestNotificationListener listener =
            new DesignQuoteRequestNotificationListener(sender, staff, notices);

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
        verify(staff, never()).activeHolders(any(), any());
    }

    @Test
    void everyActiveHolderOfTheRightIsTold() {
        var contact = new StaffContact(UUID.randomUUID(), "sale@example.test");
        when(staff.activeHolders(eq(DesignQuoteRequestNotificationListener.SALES), isNull()))
                .thenReturn(List.of(contact));

        listener.on(requested("APPROVED", "CUP12-0312345678-01", null));

        verify(notices).deliver(eq(List.of(contact)), any(InboxMessage.class), any());
    }

    @Test
    void nobodyHoldingTheRightTellsNobody() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(requested("APPROVED", "CUP12-0312345678-01", null));

        verify(notices, never()).deliver(any(), any(), any());
    }
}
