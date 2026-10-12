package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.CustomerActedOnQuote;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The Sales notice of a customer's step on a quote: who gets it, what it says, and nobody when unknown. */
class QuoteNotificationListenerTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final QuoteNotificationListener listener = new QuoteNotificationListener(
            new NotificationSender(mail, mock(RestClientFactory.class), "noreply@stockflow.local", ""));

    private static CustomerActedOnQuote event(CustomerActedOnQuote.Action action, String detail, String salesEmail) {
        return new CustomerActedOnQuote(UUID.randomUUID(), "QT-20261012-0007", UUID.randomUUID(), "Cong ty In Ly A",
                action, detail, UUID.randomUUID(), salesEmail, Instant.parse("2026-10-12T03:00:00Z"));
    }

    private SimpleMailMessage sent() {
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        return message.getValue();
    }

    @Test
    void anAcceptedQuoteIsMailedToTheSalesUserWhoSentIt() {
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, "sales@example.com"));
        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly("sales@example.com");
        assertThat(message.getFrom()).isEqualTo("noreply@stockflow.local");
        assertThat(message.getSubject()).contains("Cong ty In Ly A", "QT-20261012-0007", "chấp nhận");
        assertThat(message.getText()).contains("chuyển báo giá thành đơn hàng");
    }

    @Test
    void aChangeRequestCarriesWhatTheCustomerAsked() {
        listener.on(event(CustomerActedOnQuote.Action.CHANGES_REQUESTED, "2,200 VND at 500?", "sales@example.com"));
        assertThat(sent().getText()).contains("QT-20261012-0007", "2,200 VND at 500?", "sửa");
    }

    @Test
    void aReorderSaysTheDraftIsAtTodaysListNotTheOldDeal() {
        listener.on(event(CustomerActedOnQuote.Action.REORDER_REQUESTED, "SO-20261001-0003", "sales@example.com"));
        SimpleMailMessage message = sent();
        assertThat(message.getSubject()).contains("SO-20261001-0003");
        assertThat(message.getText()).contains("QT-20261012-0007", "SO-20261001-0003",
                "không dùng giá đã thương lượng", "xác nhận");
    }

    @Test
    void withoutARecipientNobodyIsMailed() {
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, null));
        listener.on(event(CustomerActedOnQuote.Action.ACCEPTED, null, " "));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
