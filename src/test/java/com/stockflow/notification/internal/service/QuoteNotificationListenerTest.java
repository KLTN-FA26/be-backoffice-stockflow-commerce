package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Sales notice of a customer's step on a quote: who gets it (the responsible Sales account, or the team to take
 * over - N1), what it says (numbers only, never what the customer typed - B-4), in-app first and e-mail best effort
 * (B-5), and nobody when nobody is active.
 */
class QuoteNotificationListenerTest {

    private static final Instant AT = Instant.parse("2026-10-12T03:00:00Z");

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final com.stockflow.identity.api.IdentityService identity =
            mock(com.stockflow.identity.api.IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final QuoteNotificationListener listener = new QuoteNotificationListener(
            new NotificationSender(mail, mock(RestClientFactory.class), "noreply@stockflow.local", ""),
            new StaffDirectory(identity), new StaffNotices(inbox));

    @BeforeEach
    void everyoneIsNewlyTold() {
        when(inbox.post(any(), any())).thenReturn(1);
    }

    private List<UUID> activeSales(String... emails) {
        var users = java.util.Arrays.stream(emails).map(email -> new com.stockflow.identity.api.StaffUser(UUID.randomUUID(),
                email, email, "Sales", "ACTIVE", java.util.List.of("SALES_STAFF"), java.util.List.of(), false, null, null,
                Instant.EPOCH, "admin", 0)).toList();
        when(identity.listUsers(org.mockito.ArgumentMatchers.argThat(
                (com.stockflow.identity.api.ListUsersQuery q) -> q != null && "SALES_STAFF".equals(q.roleCode())
                        && "ACTIVE".equals(q.status()) && q.page() == 0)))
                .thenReturn(com.stockflow.common.api.PageResponse.of(users, 0, 100, users.size()));
        return users.stream().map(com.stockflow.identity.api.StaffUser::userId).toList();
    }

    /**
     * The event names the Sales account by id only; its address is looked up on delivery. {@code salesEmail} is what
     * the identity module answers for it then: null for an account deleted since (404).
     */
    private CustomerActedOnQuote event(CustomerActedOnQuote.Action action, String sourceOrderNumber, String salesEmail) {
        return event(action, sourceOrderNumber, salesEmail, "ACTIVE");
    }

    private CustomerActedOnQuote event(CustomerActedOnQuote.Action action, String sourceOrderNumber, String salesEmail,
                                       String status) {
        UUID sales = UUID.randomUUID();
        if (salesEmail == null) {
            when(identity.user(sales)).thenThrow(new com.stockflow.common.error.BusinessException(
                    com.stockflow.common.error.ErrorCode.NOT_FOUND));
        } else {
            when(identity.user(sales)).thenReturn(new com.stockflow.identity.api.StaffUser(sales, "sales", salesEmail,
                    "Sales", status, List.of("SALES_STAFF"), List.of(), false, null, null, Instant.EPOCH, "admin", 0));
        }
        return new CustomerActedOnQuote(UUID.randomUUID(), "QT-20261012-0007", UUID.randomUUID(), action,
                sourceOrderNumber, sales, AT);
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
    void anAcceptedQuoteReachesTheSalesUserWhoSentItInAppAndByMail() {
        CustomerActedOnQuote event = event(CustomerActedOnQuote.Action.ACCEPTED, null, "sales@example.com");
        listener.on(event);
        InboxMessage message = posted();
        verify(inbox).post(List.of(event.salesUserId()), message);
        assertThat(message.title()).contains("QT-20261012-0007", "chấp nhận").doesNotContain("Cong ty In Ly A");
        assertThat(message.link()).isEqualTo("/admin/sales/quotes/" + event.quoteId());
        assertThat(message.dedupeKey()).isEqualTo("quote:%s:ACCEPTED:%s".formatted(event.quoteId(), AT));
        SimpleMailMessage mailed = sent();
        assertThat(mailed.getTo()).containsExactly("sales@example.com");
        assertThat(mailed.getFrom()).isEqualTo("noreply@stockflow.local");
        assertThat(mailed.getSubject()).contains("QT-20261012-0007", "chấp nhận");
        assertThat(mailed.getText()).contains("chuyển báo giá thành đơn hàng");
    }

    @Test
    void aChangeRequestPointsAtTheQuoteAndCarriesNothingTheCustomerTyped() {
        listener.on(event(CustomerActedOnQuote.Action.CHANGES_REQUESTED, null, "sales@example.com"));
        assertThat(posted().title()).contains("QT-20261012-0007", "sửa");
        assertThat(sent().getText()).contains("QT-20261012-0007", "nội dung khách gửi nằm trong báo giá", "sửa");
    }

    @Test
    void aReorderSaysTheDraftIsAtTodaysListNotTheOldDeal() {
        listener.on(event(CustomerActedOnQuote.Action.REORDER_REQUESTED, "SO-20261001-0003", "sales@example.com"));
        assertThat(posted().title()).contains("SO-20261001-0003", "QT-20261012-0007");
        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).contains("SO-20261001-0003");
        assertThat(message.getText()).contains("QT-20261012-0007", "SO-20261001-0003",
                "không dùng giá đã thương lượng", "xác nhận");
    }

    @Test
    void aGoneSalesAccountHandsTheCustomerToTheActiveSalesTeamOnceEach() {
        // N1: the responsible account is gone (the identity module answers 404), so the team takes over.
        List<UUID> team = activeSales("sale1@example.com", "sale2@example.com", "sale1@example.com");
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, null));
        verify(inbox, times(3)).post(any(), any());
        InboxMessage handOver = posted();
        assertThat(handOver.body()).contains("tiếp nhận");
        verify(inbox).post(List.of(team.get(0)), handOver);
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sale1@example.com", "sale2@example.com");
        assertThat(message.getSubject()).startsWith("[Cần tiếp nhận]").contains("QT-20261012-0007");
        assertThat(message.getText()).contains("không còn hoạt động", "tiếp nhận", "chuyển báo giá thành đơn hàng");
    }

    @Test
    void aReorderOfAGoneSalesAccountAlsoReachesTheTeam() {
        activeSales("sale2@example.com");
        listener.on(event(CustomerActedOnQuote.Action.REORDER_REQUESTED, "SO-20261001-0003", " "));
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sale2@example.com");
        assertThat(message.getText()).contains("SO-20261001-0003", "không dùng giá đã thương lượng");
    }

    @Test
    void aRedeliveredEventTellsNobodyTwiceAndAMailOutageNeverFailsIt() {
        when(inbox.post(any(), any())).thenReturn(0);
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, "sales@example.com"));
        verify(mail, never()).send(any(SimpleMailMessage.class));

        when(inbox.post(any(), any())).thenReturn(1);
        doThrow(new org.springframework.mail.MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatCode(() -> listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, "sales@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void aSalesAccountLockedSinceThePublicationIsNotWrittenToTheTeamIs() {
        activeSales("sale3@example.com");
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, "locked@example.com", "LOCKED"));
        assertThat(sent().getTo()).containsExactly("sale3@example.com");
    }

    @Test
    void theStoredEventCarriesNoNameAndNoAddress() {
        // Review of #109, item 10: the event lives in event_publication; ids and document numbers only.
        assertThat(java.util.Arrays.stream(CustomerActedOnQuote.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("quoteId", "quoteNumber", "customerId", "action", "sourceOrderNumber", "salesUserId",
                        "occurredAt");
    }

    @Test
    void withNoActiveSalesAtAllNobodyIsToldAndItIsLogged() {
        activeSales();
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, null));
        verify(inbox, never()).post(any(), any());
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
