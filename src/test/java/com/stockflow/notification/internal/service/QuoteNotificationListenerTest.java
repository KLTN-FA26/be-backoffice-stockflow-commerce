package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Sales notice of a customer's step on a quote: who gets it - whoever looks after the customer today, the owner or
 * the cover, else the Sales Managers marked "cần phân công", never every salesperson (00 §6 and 14 §4.4 step 20 as
 * changed on 2026-11-28, replacing N1) - what it says (numbers only, never what the customer typed - B-4), in-app first
 * and e-mail best effort (B-5), and nobody when nobody is active.
 */
class QuoteNotificationListenerTest {

    private static final Instant AT = Instant.parse("2026-10-12T03:00:00Z");

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final com.stockflow.identity.api.IdentityService identity =
            mock(com.stockflow.identity.api.IdentityService.class);
    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final Inbox inbox = mock(Inbox.class);
    private final QuoteNotificationListener listener = new QuoteNotificationListener(
            new NotificationSender(new SmtpEmailTransport(mail), "noreply@stockflow.local"),
            new SalesNotices(audiences, new StaffDirectory(identity), mock(SalesStaff.class)), new StaffNotices(inbox));

    @BeforeEach
    void everyoneIsNewlyTold() {
        when(inbox.post(any(), any())).thenReturn(1);
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        when(identity.user(id)).thenReturn(new com.stockflow.identity.api.StaffUser(id, "u-" + id, email, "Name",
                "ACTIVE", List.of("SALES_STAFF"), List.of(), false, null, null, Instant.EPOCH, "admin", null, 0));
        return id;
    }

    private CustomerActedOnQuote event(CustomerActedOnQuote.Action action, SalesAudience audience) {
        UUID customer = UUID.randomUUID();
        when(audiences.ofCustomer(customer)).thenReturn(Optional.ofNullable(audience));
        // The sender of the quote is named by the event and no longer decides anything.
        return new CustomerActedOnQuote(UUID.randomUUID(), "QT-20261012-0007", customer, action, UUID.randomUUID(), AT);
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
    @DisplayName("00 §6, case 3: an accepted quote reaches the customer's owner, in-app and by mail, unmarked")
    void anAcceptedQuoteReachesTheOwnerInAppAndByMail() {
        UUID owner = account("owner@example.com");
        CustomerActedOnQuote event = event(CustomerActedOnQuote.Action.ACCEPTED, new SalesAudience(List.of(owner),
                false));
        listener.on(event);
        InboxMessage message = posted();
        verify(inbox).post(List.of(owner), message);
        assertThat(message.title()).contains("QT-20261012-0007", "chấp nhận").doesNotContain("Cong ty In Ly A");
        assertThat(message.body()).isNull();
        assertThat(message.link()).isEqualTo("/admin/sales/quotes/" + event.quoteId());
        assertThat(message.dedupeKey()).isEqualTo("quote:%s:ACCEPTED:%s".formatted(event.quoteId(), AT));
        SimpleMailMessage mailed = sent();
        assertThat(mailed.getTo()).containsExactly("owner@example.com");
        assertThat(mailed.getFrom()).isEqualTo("noreply@stockflow.local");
        assertThat(mailed.getSubject()).contains("QT-20261012-0007", "chấp nhận").doesNotStartWith("[");
        assertThat(mailed.getText()).contains("chuyển báo giá thành đơn hàng");
    }

    @Test
    @DisplayName("Case 11: a customer moved after the quote was sent - the new owner hears, not the sender")
    void theOwnerOfTodayHearsNotTheSender() {
        UUID newOwner = account("new@example.com");
        CustomerActedOnQuote event = event(CustomerActedOnQuote.Action.CHANGES_REQUESTED,
                new SalesAudience(List.of(newOwner), false));
        listener.on(event);
        InboxMessage message = posted();
        verify(inbox).post(List.of(newOwner), message);
        verify(inbox, never()).post(List.of(event.salesUserId()), message);
        assertThat(message.title()).contains("QT-20261012-0007", "sửa");
        assertThat(sent().getText()).contains("QT-20261012-0007", "nội dung khách gửi nằm trong báo giá", "sửa");
    }

    @Test
    @DisplayName("BR-S07, case 5: while the owner is on leave the cover hears")
    void theCoverHearsWhileTheOwnerIsAway() {
        UUID cover = account("cover@example.com");
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, new SalesAudience(List.of(cover), false)));
        InboxMessage message = posted();
        verify(inbox).post(List.of(cover), message);
        assertThat(sent().getTo()).containsExactly("cover@example.com");
    }

    @Test
    @DisplayName("D6, case 8: no active owner - the Sales Managers hear, marked 'cần phân công', never every Sales")
    void withNoActiveOwnerTheManagersHearMarked() {
        UUID m1 = account("m1@example.com");
        UUID m2 = account("m2@example.com");
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, new SalesAudience(List.of(m1, m2), true)));
        verify(inbox, times(2)).post(any(), any());
        InboxMessage marked = posted();
        assertThat(marked.body()).contains("Cần phân công");
        verify(inbox).post(List.of(m1), marked);
        verify(inbox).post(List.of(m2), marked);
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("m1@example.com", "m2@example.com");
        assertThat(message.getSubject()).startsWith("[Cần phân công]").contains("QT-20261012-0007");
        assertThat(message.getText()).contains("Sales Manager", "chuyển báo giá thành đơn hàng");
        // Never the whole team: nobody is looked up by role.
        verify(identity, never()).listUsers(any());
    }

    @Test
    void aRedeliveredEventTellsNobodyTwiceAndAMailOutageNeverFailsIt() {
        UUID owner = account("owner@example.com");
        when(inbox.post(any(), any())).thenReturn(0);
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, new SalesAudience(List.of(owner), false)));
        verify(mail, never()).send(any(SimpleMailMessage.class));

        when(inbox.post(any(), any())).thenReturn(1);
        doThrow(new org.springframework.mail.MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatCode(() -> listener.on(event(CustomerActedOnQuote.Action.ACCEPTED,
                new SalesAudience(List.of(owner), false)))).doesNotThrowAnyException();
    }

    @Test
    void theStoredEventCarriesNoNameAndNoAddress() {
        // Review of #109, item 10: the event lives in event_publication; ids and document numbers only.
        assertThat(java.util.Arrays.stream(CustomerActedOnQuote.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("quoteId", "quoteNumber", "customerId", "action", "salesUserId", "occurredAt");
    }

    @Test
    @DisplayName("Case 25: nobody to tell (no manager, no admin active) - nobody is told and it is logged")
    void withNobodyToTellNobodyIsTold() {
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, new SalesAudience(List.of(), true)));
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null));
        verify(inbox, never()).post(any(), any());
        verifyNoInteractions(mail);
    }
}
