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
    private final com.stockflow.customer.api.ResponsibleSales responsible =
            mock(com.stockflow.customer.api.ResponsibleSales.class);
    private final com.stockflow.order.api.CustomerDocuments documents =
            mock(com.stockflow.order.api.CustomerDocuments.class);
    private final QuotePriceReviewNotices notices = new QuotePriceReviewNotices(identities, inbox,
            new SalesRecipients(responsible, java.time.Clock.systemUTC()), documents);

    /** The quote belongs to a customer whom {@code who} looks after today. */
    private void quoteOfCustomerLookedAfterBy(UUID quoteId, com.stockflow.customer.api.ResponsibleKind kind,
                                              List<UUID> who) {
        UUID customer = UUID.randomUUID();
        when(documents.quote(quoteId)).thenReturn(java.util.Optional.of(new com.stockflow.order.api.CustomerDocument(
                quoteId, customer, "QT-9", "DRAFT", null, null, null, null)));
        com.stockflow.customer.api.Responsible answer = kind == com.stockflow.customer.api.ResponsibleKind.MANAGERS
                ? new com.stockflow.customer.api.Responsible(customer, kind, null, null, who)
                : new com.stockflow.customer.api.Responsible(customer, kind, who.get(0), who.get(0), List.of());
        when(responsible.responsibleFor(anyCollection(), any())).thenReturn(java.util.Map.of(customer, answer));
    }

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

    @Test
    @org.junit.jupiter.api.DisplayName("Case 26, 14 §4.4 step 20: the customer moved while the quote waited - the new "
            + "owner hears the decision too")
    @SuppressWarnings("unchecked")
    void theNewOwnerHearsTheDecisionToo() {
        UUID asker = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        quoteOfCustomerLookedAfterBy(quote, com.stockflow.customer.api.ResponsibleKind.OWNER, List.of(newOwner));

        notices.on(new QuotePriceReviewDecided(quote, "QT-9", 1, UUID.randomUUID(), "APPROVED", asker,
                UUID.randomUUID(), null));

        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        verify(inbox, org.mockito.Mockito.times(2)).post(to.capture(), any());
        assertThat(to.getAllValues()).containsExactly(List.of(asker), List.of(newOwner));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("The owner who asked hears once; the manager who decided is not told of their "
            + "own decision even as the fallback")
    @SuppressWarnings("unchecked")
    void nobodyHearsTwiceAndTheDeciderIsLeftOut() {
        UUID asker = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        quoteOfCustomerLookedAfterBy(quote, com.stockflow.customer.api.ResponsibleKind.OWNER, List.of(asker));
        notices.on(new QuotePriceReviewDecided(quote, "QT-9", 1, UUID.randomUUID(), "APPROVED", asker,
                UUID.randomUUID(), null));
        verify(inbox, org.mockito.Mockito.times(1)).post(anyCollection(), any());

        UUID decider = UUID.randomUUID();
        UUID otherManager = UUID.randomUUID();
        UUID quote2 = UUID.randomUUID();
        quoteOfCustomerLookedAfterBy(quote2, com.stockflow.customer.api.ResponsibleKind.MANAGERS,
                List.of(decider, otherManager));
        notices.on(new QuotePriceReviewDecided(quote2, "QT-10", 1, UUID.randomUUID(), "REJECTED", asker, decider,
                "Gia qua thap"));
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, org.mockito.Mockito.times(3)).post(to.capture(), message.capture());
        assertThat(to.getAllValues().get(2)).containsExactly(otherManager);
        assertThat(message.getAllValues().get(2).body()).contains("Cần phân công");
    }

    private static StaffContact staff(UUID id) {
        return new StaffContact(id, "staff-" + id + "@example.com");
    }
}
