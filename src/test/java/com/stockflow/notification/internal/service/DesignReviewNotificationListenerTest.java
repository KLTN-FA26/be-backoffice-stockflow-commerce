package com.stockflow.notification.internal.service;

import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who hears of a design waiting for review: whoever holds the right to review designs, system-wide, once per
 * submission; never the design's name, which the customer typed. The inbox and the mail transport are mocks.
 */
class DesignReviewNotificationListenerTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final DesignReviewNotificationListener listener = new DesignReviewNotificationListener(
            new NotificationSender(new SmtpEmailTransport(mock(JavaMailSender.class)), "noreply@stockflow.local"),
            new StaffDirectory(identity), new StaffNotices(inbox));

    private final DesignSubmittedForReview event = new DesignSubmittedForReview(UUID.randomUUID(), UUID.randomUUID(),
            "CUP12-001", Instant.parse("2026-11-08T03:00:00Z"));

    @Test
    void theReviewersAreToldOncePerSubmission() {
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("design-designs:APPROVE", null))
                .thenReturn(List.of(new StaffContact(manager, "manager@example.com")));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(manager)), message.capture());
        assertThat(message.getValue().category()).isEqualTo("DESIGN_REVIEW");
        assertThat(message.getValue().title()).contains("CUP12-001");
        assertThat(message.getValue().link()).isEqualTo("/admin/designs/review-queue");
        assertThat(message.getValue().sourceId()).isEqualTo(event.designId().toString());
        assertThat(message.getValue().dedupeKey())
                .isEqualTo("design-review:" + event.designId() + ":" + event.submittedAt().toEpochMilli());
    }

    @Test
    void nobodyToTellIsLoggedNotThrown() {
        when(identity.notificationRecipientsHolding("design-designs:APPROVE", null)).thenReturn(List.of());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
    }
}
