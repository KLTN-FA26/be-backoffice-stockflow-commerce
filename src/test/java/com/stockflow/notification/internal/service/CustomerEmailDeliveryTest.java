package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.service.CustomerEmailDelivery.CustomerEmailDeferred;
import com.stockflow.notification.internal.service.CustomerEmailDelivery.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One customer message: sent once, skipped with a reason, refused for good, or retried with a back-off. */
class CustomerEmailDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private final CustomerEmailLog log = mock(CustomerEmailLog.class);
    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final EmailTransport transport = mock(EmailTransport.class);
    private final UUID customer = UUID.randomUUID();
    private CustomerMessage message;

    private CustomerEmailDelivery delivery(EmailProperties properties) {
        return new CustomerEmailDelivery(log, customers, new EmailTemplates(), transport, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private final CustomerEmailDelivery delivery = delivery(properties());

    @BeforeEach
    void freshMessage() {
        message = new CustomerEmailComposer(properties()).orderPlaced(placed(customer, null)).orElseThrow();
        when(log.state(anyString())).thenReturn(new CustomerEmailLog.State(false, false, 0));
        when(customers.contact(customer)).thenReturn(Optional.of(
                new CustomerContact(customer, "Công ty TNHH In Ly An Phát", " buyer@cup-maker.vn ")));
        when(transport.send(any())).thenReturn(new EmailReceipt(EmailProvider.RESEND, "e-1"));
    }

    @Test
    @DisplayName("sent: addressed from the directory, keyed by the message's reference, recorded with the provider id")
    void sent() {
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.SENT);

        var sent = ArgumentCaptor.forClass(Email.class);
        verify(transport).send(sent.capture());
        assertThat(sent.getValue().to()).containsExactly("buyer@cup-maker.vn");
        assertThat(sent.getValue().idempotencyKey()).isEqualTo(message.reference());
        assertThat(sent.getValue().category()).isEqualTo("order_placed");
        assertThat(sent.getValue().from()).isEqualTo(EmailFixtures.FROM);
        assertThat(sent.getValue().html()).contains("Kính gửi Công ty TNHH In Ly An Phát,");
        verify(log).sent(message, "buyer@cup-maker.vn", new EmailReceipt(EmailProvider.RESEND, "e-1"), 1);
    }

    @Test
    @DisplayName("a redelivered event finds the message sent, or settled, and sends nothing")
    void duplicates() {
        when(log.state(message.reference())).thenReturn(new CustomerEmailLog.State(true, false, 0));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.ALREADY_SENT);
        when(log.state(message.reference())).thenReturn(new CustomerEmailLog.State(false, true, 2));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.ALREADY_SETTLED);
        verifyNoInteractions(transport, customers);
    }

    @Test
    @DisplayName("two deliveries of one message at once: the second to record it reads 'already sent'")
    void concurrentDelivery() {
        doThrow(new DataIntegrityViolationException("uk_delivery_log_external_reference")).when(log)
                .sent(any(), anyString(), any(), anyInt());
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.ALREADY_SENT);
    }

    @Test
    @DisplayName("no customer, no address, a malformed address: skipped with the reason, final, nothing thrown")
    void skips() {
        when(customers.contact(customer)).thenReturn(Optional.empty());
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "CUSTOMER_NOT_FOUND");

        when(customers.contact(customer)).thenReturn(Optional.of(new CustomerContact(customer, "A", "  ")));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "RECIPIENT_MISSING");

        when(customers.contact(customer)).thenReturn(Optional.of(new CustomerContact(customer, "A", "a@b")));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "RECIPIENT_INVALID");

        CustomerMessage guestWithoutAddress = new CustomerEmailComposer(properties()).orderPlaced(placed(null, null))
                .orElseThrow();
        assertThat(delivery.deliver(guestWithoutAddress)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(guestWithoutAddress, "guest", "NO_RECIPIENT");
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("customer mail switched off: skipped and recorded, the directory not even asked")
    void switchedOff() {
        assertThat(delivery(properties(null, false, 10)).deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "CUSTOMER_EMAIL_DISABLED");
        verifyNoInteractions(transport, customers);
    }

    @Test
    @DisplayName("refused for this message (422) or for every message (bad key): recorded final, nothing thrown")
    void refused() {
        when(transport.send(any())).thenThrow(EmailDeliveryException.rejected("RESEND_REJECTED_422", null));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.REFUSED);
        verify(log).failed(message, "buyer@cup-maker.vn", "RESEND_REJECTED_422", 1, null);

        doThrow(EmailDeliveryException.misconfigured("RESEND_UNAUTHORIZED", null)).when(transport).send(any());
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.REFUSED);
        verify(log).failed(message, "buyer@cup-maker.vn", "RESEND_UNAUTHORIZED", 1, null);
    }

    @Test
    @DisplayName("worth retrying: recorded with its next attempt time, and thrown so the publication stays for a retry")
    void retryable() {
        when(log.state(message.reference())).thenReturn(new CustomerEmailLog.State(false, false, 2));
        when(transport.send(any())).thenThrow(EmailDeliveryException.retryable("RESEND_UNAVAILABLE", null, null));

        assertThatThrownBy(() -> delivery.deliver(message)).isInstanceOf(CustomerEmailDeferred.class)
                .hasMessageContaining(message.reference()).hasMessageContaining("RESEND_UNAVAILABLE")
                .hasNoCause();
        // Third attempt: one minute doubled twice.
        verify(log).failed(message, "buyer@cup-maker.vn", "RESEND_UNAVAILABLE", 3, NOW.plus(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("a rate limit waits at least as long as the provider asked")
    void retryAfterIsRespected() {
        when(transport.send(any())).thenThrow(
                EmailDeliveryException.retryable("RESEND_RATE_LIMITED", Duration.ofMinutes(30), null));
        assertThatThrownBy(() -> delivery.deliver(message)).isInstanceOf(CustomerEmailDeferred.class);
        verify(log).failed(message, "buyer@cup-maker.vn", "RESEND_RATE_LIMITED", 1, NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("the last attempt allowed is final: given up, recorded, nothing thrown")
    void givesUp() {
        when(log.state(message.reference())).thenReturn(new CustomerEmailLog.State(false, false, 9));
        when(transport.send(any())).thenThrow(EmailDeliveryException.retryable("RESEND_TIMEOUT", null, null));
        assertThat(delivery.deliver(message)).isEqualTo(Outcome.GAVE_UP);
        verify(log).failed(eq(message), eq("buyer@cup-maker.vn"), eq("RESEND_TIMEOUT"), eq(10), isNull());
    }

    @Test
    @DisplayName("an unexpected transport failure is bounded like a 5xx, not lost and not retried for ever")
    void unexpectedFailure() {
        when(transport.send(any())).thenThrow(new IllegalStateException("library surprise"));
        assertThatThrownBy(() -> delivery.deliver(message)).isInstanceOf(CustomerEmailDeferred.class);
        verify(log).failed(eq(message), anyString(), eq("TRANSPORT_ERROR"), eq(1), any(Instant.class));
    }

    @Test
    @DisplayName("a message that cannot be rendered is a bug: recorded final, never sent, never retried")
    void renderFailure() {
        CustomerMessage broken = new CustomerMessage("order.placed", "o-1", customer, null, addressee -> {
            throw new IllegalStateException("missing key");
        });
        assertThat(delivery.deliver(broken)).isEqualTo(Outcome.GAVE_UP);
        verify(log).failed(broken, "buyer@cup-maker.vn", "RENDER_FAILED", 1, null);
        verify(transport, never()).send(any());
    }

    @Test
    @DisplayName("back-off: one minute doubled per failure, never below Retry-After, never above the maximum")
    void backOff() {
        assertThat(delivery.delay(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(delivery.delay(4, null)).isEqualTo(Duration.ofMinutes(8));
        assertThat(delivery.delay(30, null)).isEqualTo(Duration.ofHours(6));
        assertThat(delivery.delay(1, Duration.ofSeconds(5))).isEqualTo(Duration.ofMinutes(1));
        assertThat(delivery.delay(1, Duration.ofDays(1))).isEqualTo(Duration.ofHours(6));
    }
}
