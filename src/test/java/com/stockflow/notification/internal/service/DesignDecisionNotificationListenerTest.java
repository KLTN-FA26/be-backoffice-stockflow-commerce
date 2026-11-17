package com.stockflow.notification.internal.service;

import com.stockflow.contracts.DesignReviewed;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The customer hears of the Sales Manager's decision on their design, in the app, once per decision. */
class DesignDecisionNotificationListenerTest {

    private final Inbox inbox = mock(Inbox.class);
    private final DesignDecisionNotificationListener listener = new DesignDecisionNotificationListener(inbox);

    @Test
    void theOwnerIsToldOfAnApprovalWithItsOwnSku() {
        UUID owner = UUID.randomUUID();
        var event = new DesignReviewed(UUID.randomUUID(), owner, "APPROVED", "CUP12-0312345678-01",
                Instant.parse("2026-11-08T03:00:00Z"), null);

        listener.on(event);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(owner)), message.capture());
        assertThat(message.getValue().title()).contains("CUP12-0312345678-01");
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(message.getValue().dedupeKey())
                .isEqualTo("design-decision:" + event.designId() + ":" + event.decidedAt().toEpochMilli());
    }

    @Test
    void aReturnIsAWarningAndADesignWithoutOwnerTellsNobody() {
        var returned = DesignDecisionNotificationListener.message(new DesignReviewed(UUID.randomUUID(),
                UUID.randomUUID(), "RETURNED", "CUP-12", Instant.now(), null));
        assertThat(returned.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(returned.body()).contains("CUP-12");

        listener.on(new DesignReviewed(UUID.randomUUID(), null, "RETURNED", "CUP-12", Instant.now(), null));
        verify(inbox, never()).post(any(), any());
    }
}
