package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceMail;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WF-05 E5 (plan §1.4): the e-mail that carries an issued e-invoice to its buyer - worded by
 * {@link EInvoiceEmailComposer}, sent by the delivery every customer message goes through. It goes to the address the
 * invoice names, once, with the PDF and the XML attached and the lookup code in its text; it is retried when the mail
 * provider or the file store is down; it is not sent at all while customer e-mail is switched off
 * ({@code CUSTOMER_EMAIL_ENABLED}).
 */
class EInvoiceMailTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final byte[] PDF = "%PDF-1.7 the invoice".getBytes(StandardCharsets.UTF_8);
    private static final byte[] XML = "<HDon><SHDon>42</SHDon></HDon>".getBytes(StandardCharsets.UTF_8);

    private final EInvoiceDirectory einvoices = mock(EInvoiceDirectory.class);
    private final CustomerEmailLog log = mock(CustomerEmailLog.class);
    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final EmailTransport transport = mock(EmailTransport.class);
    private final UUID customer = UUID.randomUUID();
    private final EInvoiceIssued event = new EInvoiceIssued(UUID.randomUUID(), "EINV-20261112-0001",
            UUID.randomUUID(), "SO-20261101-0007", UUID.randomUUID(), customer, NOW);
    private final String reference = "customer-mail:einvoice.issued:" + event.einvoiceId();

    private CustomerMessage message;

    private CustomerEmailDelivery delivery(EmailProperties properties) {
        return new CustomerEmailDelivery(log, customers, new EmailTemplates(), transport, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EInvoiceMail mail(String recipient) {
        return new EInvoiceMail(event.einvoiceId(), customer, "CONG TY TNHH IN LY AN PHAT", recipient,
                "1C26TSB-00000042", "SO-20261101-0007", LocalDate.of(2026, 11, 12), new BigDecimal("440000"), "VND",
                "SBX-CQT-0042", "SBX-LOOKUP-0042");
    }

    @BeforeEach
    void anIssuedInvoice() {
        message = new EInvoiceEmailComposer(einvoices, new CustomerEmailComposer(properties())).issued(event)
                .orElseThrow();
        when(log.state(anyString())).thenReturn(new CustomerEmailLog.State(false, false, 0));
        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.of(mail(" ke.toan@an-phat.vn ")));
        when(einvoices.files(event.einvoiceId())).thenReturn(List.of(
                new EInvoiceMail.File("1C26TSB-00000042.pdf", "application/pdf", PDF),
                new EInvoiceMail.File("1C26TSB-00000042.xml", "application/xml", XML)));
        when(transport.send(any())).thenReturn(new EmailReceipt(EmailProvider.RESEND, "e-1"));
    }

    @Test
    @DisplayName("one message per invoice, named by the invoice; asking which message the event calls for reads nothing")
    void oneMessagePerInvoice() {
        assertThat(message.reference()).isEqualTo(reference);
        assertThat(message.category()).isEqualTo("einvoice_issued");
        assertThat(message.customerId()).isEqualTo(customer);
        assertThat(message.toString()).doesNotContain("an-phat");
        verifyNoInteractions(einvoices);
    }

    @Test
    @DisplayName("sent once to the address the invoice names, with the PDF, the XML and the lookup code")
    void sentWithItsFilesAndLookupCode() {
        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.SENT);

        var sent = ArgumentCaptor.forClass(Email.class);
        verify(transport).send(sent.capture());
        Email email = sent.getValue();
        assertThat(email.to()).as("the invoice's own e-mail, trimmed").containsExactly("ke.toan@an-phat.vn");
        assertThat(email.idempotencyKey()).isEqualTo(reference);
        assertThat(email.category()).isEqualTo("einvoice_issued");
        assertThat(email.subject()).isEqualTo("Hoá đơn điện tử 1C26TSB-00000042");
        assertThat(email.text()).contains("Kính gửi CONG TY TNHH IN LY AN PHAT,", "Số hoá đơn: 1C26TSB-00000042",
                "Ngày lập: 12/11/2026", "SO-20261101-0007", "Mã của cơ quan thuế: SBX-CQT-0042",
                "Mã tra cứu: SBX-LOOKUP-0042", "440.000");
        assertThat(email.html()).contains("SBX-LOOKUP-0042", "1C26TSB-00000042", "SBX-CQT-0042");
        assertThat(email.attachments()).extracting(Email.Attachment::name, Email.Attachment::contentType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1C26TSB-00000042.pdf", "application/pdf"),
                        org.assertj.core.groups.Tuple.tuple("1C26TSB-00000042.xml", "application/xml"));
        assertThat(email.attachments().get(0).content()).isEqualTo(PDF);
        assertThat(email.attachments().get(1).content()).isEqualTo(XML);
        assertThat(email.toString()).as("a logged e-mail names no file content, code or address")
                .doesNotContain("SBX-LOOKUP-0042", "an-phat").contains("attachments=2");
        verify(log).sent(message, "ke.toan@an-phat.vn", new EmailReceipt(EmailProvider.RESEND, "e-1"), 1);
        // The customer's contact address - where order mail goes - is not asked for.
        verifyNoInteractions(customers);
    }

    @Test
    @DisplayName("the event again - a redelivery, the retry job: found sent, nothing read, nothing sent twice")
    void sentOnce() {
        when(log.state(reference)).thenReturn(new CustomerEmailLog.State(true, false, 0));

        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.ALREADY_SENT);

        verifyNoInteractions(transport, einvoices, customers);
    }

    @Test
    @DisplayName("EINVOICE_EMAIL_ENABLED off: recorded as skipped, no invoice read, no file fetched, nothing sent")
    void switchedOff() {
        assertThat(delivery(properties(null, true, false, 10)).deliver(message)).isEqualTo(Outcome.SKIPPED);

        verify(log).skipped(message, "customer:" + customer, "EINVOICE_EMAIL_DISABLED");
        verifyNoInteractions(transport, einvoices, customers);
    }

    @Test
    @DisplayName("E0 M2: customer mail switched off does not hold back the invoice - its delivery is the law's, not ours")
    void customerMailOffStillSendsTheInvoice() {
        assertThat(delivery(properties(null, false, true, 10)).deliver(message)).isEqualTo(Outcome.SENT);

        verify(transport).send(any());
    }

    @Test
    @DisplayName("an invoice that is gone or was never issued, or names no address: skipped for good with the reason")
    void nothingToSendOrNowhereToSendIt() {
        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.empty());
        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "SOURCE_NOT_FOUND");

        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.of(mail(null)));
        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "RECIPIENT_MISSING");

        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.of(mail("ke.toan@an-phat")));
        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.SKIPPED);
        verify(log).skipped(message, "customer:" + customer, "RECIPIENT_INVALID");

        verify(einvoices, never()).files(any());
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("the mail provider is down: the attempt is recorded with its next time and kept for the retry; the "
            + "retry sends the same message - same key, same files")
    void retriedWhenTheProviderIsDown() {
        when(transport.send(any())).thenThrow(EmailDeliveryException.retryable("RESEND_UNAVAILABLE", null, null))
                .thenReturn(new EmailReceipt(EmailProvider.RESEND, "e-2"));

        assertThatThrownBy(() -> delivery(properties()).deliver(message)).isInstanceOf(CustomerEmailDeferred.class)
                .hasMessageContaining(reference).hasMessageContaining("RESEND_UNAVAILABLE");
        verify(log).failed(message, "ke.toan@an-phat.vn", "RESEND_UNAVAILABLE", 1, NOW.plus(Duration.ofMinutes(1)));

        when(log.state(reference)).thenReturn(new CustomerEmailLog.State(false, false, 1));
        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.SENT);

        var sent = ArgumentCaptor.forClass(Email.class);
        verify(transport, times(2)).send(sent.capture());
        Email first = sent.getAllValues().get(0);
        Email second = sent.getAllValues().get(1);
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey()).isEqualTo(reference);
        assertThat(second.to()).isEqualTo(first.to());
        assertThat(second.subject()).isEqualTo(first.subject());
        assertThat(second.attachments()).hasSize(2);
        assertThat(second.attachments().get(0).content()).isEqualTo(first.attachments().get(0).content());
        assertThat(second.attachments().get(1).content()).isEqualTo(first.attachments().get(1).content());
        verify(log).sent(message, "ke.toan@an-phat.vn", new EmailReceipt(EmailProvider.RESEND, "e-2"), 2);
    }

    @Test
    @DisplayName("the file store is down: an outage, not the message's fault - recorded ATTACHMENT_UNAVAILABLE, kept "
            + "for the retry, and never sent without its files")
    void retriedWhenTheFilesCannotBeRead() {
        when(einvoices.files(event.einvoiceId())).thenThrow(new IllegalStateException("object store timed out"));

        assertThatThrownBy(() -> delivery(properties()).deliver(message)).isInstanceOf(CustomerEmailDeferred.class)
                .hasMessageContaining("ATTACHMENT_UNAVAILABLE");

        verify(log).failed(message, "ke.toan@an-phat.vn", "ATTACHMENT_UNAVAILABLE", 1, NOW.plus(Duration.ofMinutes(1)));
        verify(transport, never()).send(any());
    }

    @Test
    @DisplayName("a provider that refuses the message for good is not retried")
    void refusedIsFinal() {
        when(transport.send(any())).thenThrow(EmailDeliveryException.rejected("RESEND_REJECTED_422", null));

        assertThat(delivery(properties()).deliver(message)).isEqualTo(Outcome.REFUSED);

        verify(log).failed(message, "ke.toan@an-phat.vn", "RESEND_REJECTED_422", 1, null);
    }
}
