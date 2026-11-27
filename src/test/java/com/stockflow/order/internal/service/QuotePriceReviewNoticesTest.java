package com.stockflow.order.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** kltn-docs 14 BR-10 (2026-11-26): who hears about a quote's prices waiting, and about the decision. */
class QuotePriceReviewNoticesTest {

    private final IdentityService identities = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final QuotePriceReviewNotices notices = new QuotePriceReviewNotices(identities, inbox);

    @Test
    @SuppressWarnings("unchecked")
    void theApproversAreToldButNotWhoeverAsked() {
        UUID asker = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(identities.notificationRecipientsHolding(QuotePriceReviewNotices.APPROVES_PRICES, null))
                .thenReturn(List.of(staff(asker), staff(manager)));

        notices.on(new QuotePriceReviewRequested(UUID.randomUUID(), "QT-1", 2, UUID.randomUUID(), asker, 1));

        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactly(manager);
        assertThat(message.getValue().title()).contains("QT-1");
    }

    @Test
    void withNobodyElseToApproveNothingIsPosted() {
        UUID asker = UUID.randomUUID();
        when(identities.notificationRecipientsHolding(QuotePriceReviewNotices.APPROVES_PRICES, null))
                .thenReturn(List.of(staff(asker)));

        notices.on(new QuotePriceReviewRequested(UUID.randomUUID(), "QT-1", 1, UUID.randomUUID(), asker, 1));

        verify(inbox, never()).post(anyCollection(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSalesAccountThatAskedHearsTheDecisionWithItsReason() {
        UUID asker = UUID.randomUUID();

        notices.on(new QuotePriceReviewDecided(UUID.randomUUID(), "QT-9", 1, UUID.randomUUID(), "REJECTED", asker,
                UUID.randomUUID(), "Gia qua thap"));

        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactly(asker);
        assertThat(message.getValue().body()).contains("Gia qua thap");
    }

    private static StaffContact staff(UUID id) {
        return new StaffContact(id, "staff-" + id + "@example.com");
    }
}
