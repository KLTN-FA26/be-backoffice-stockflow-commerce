package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceMail;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.internal.service.CustomerEmailDelivery.CustomerEmailDeferred;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 E5 (plan §1.4): an issued e-invoice reaches its buyer two ways - a notice in the portal account's inbox,
 * always, and the e-mail with its files, handed to the customer-mail delivery (which sends, skips or retries it).
 */
class EInvoiceIssuedNotificationListenerTest {

    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final CustomerEmailDelivery delivery = mock(CustomerEmailDelivery.class);
    private final EInvoiceDirectory einvoices = mock(EInvoiceDirectory.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final EInvoiceIssuedNotificationListener listener = new EInvoiceIssuedNotificationListener(customers, inbox,
            new EInvoiceEmailComposer(einvoices, new CustomerEmailComposer(properties())), delivery, einvoices, staff);

    private final UUID customer = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final EInvoiceIssued event = new EInvoiceIssued(UUID.randomUUID(), "EINV-20261112-0001",
            UUID.randomUUID(), "SO-20261101-0007", UUID.randomUUID(), customer, Instant.parse("2026-11-12T08:00:00Z"));

    @Test
    @DisplayName("the portal account is told once per invoice, in system text, and the e-mail is handed to the delivery")
    void thePortalIsToldAndTheMailIsHandedOver() {
        when(customers.portalAccount(customer)).thenReturn(Optional.of(account));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var notice = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(account)), notice.capture());
        InboxMessage sent = notice.getValue();
        assertThat(sent.category()).isEqualTo("EINVOICE_ISSUED");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(sent.title()).isEqualTo("Hoá đơn điện tử của đơn SO-20261101-0007 đã được phát hành");
        assertThat(sent.body()).contains("SO-20261101-0007", "PDF", "XML");
        assertThat(sent.link()).isEqualTo("/einvoices/" + event.einvoiceId());
        assertThat(sent.sourceId()).isEqualTo(event.einvoiceId().toString());
        assertThat(sent.dedupeKey()).as("one notice per invoice").isEqualTo("einvoice-issued:" + event.einvoiceId());

        var mail = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(delivery).deliver(mail.capture());
        assertThat(mail.getValue().reference()).isEqualTo("customer-mail:einvoice.issued:" + event.einvoiceId());
    }

    @Test
    @DisplayName("a customer without an activated portal account gets no notice, and still the e-mail")
    void noPortalAccountStillMails() {
        when(customers.portalAccount(customer)).thenReturn(Optional.empty());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
        verify(delivery).deliver(any());
    }

    @Test
    @DisplayName("the notice first: a mail attempt kept for the retry throws after the portal was told, so the "
            + "publication stays and the buyer does not wait for the mail provider")
    void theNoticeDoesNotWaitForTheMail() {
        when(customers.portalAccount(customer)).thenReturn(Optional.of(account));
        when(delivery.deliver(any())).thenThrow(new CustomerEmailDeferred("customer-mail:einvoice.issued:x",
                "RESEND_UNAVAILABLE", 1));

        assertThatThrownBy(() -> listener.on(event)).isInstanceOf(CustomerEmailDeferred.class);

        InOrder order = inOrder(inbox, delivery);
        order.verify(inbox).post(eq(List.of(account)), any());
        order.verify(delivery).deliver(any());
    }

    private EInvoiceMail mailTo(String email) {
        return new EInvoiceMail(event.einvoiceId(), customer, "Cong ty Tra Sua", email, "1C26TSE-00000589",
                event.orderNumber(), LocalDate.of(2026, 11, 12), new BigDecimal("2740000"), "VND", "M1-26-X",
                "REF");
    }

    @Test
    @DisplayName("HĐ-8 (Q-22 BR-06): issued without a buyer e-mail - Sales are told once to record it and send the "
            + "invoice on, and the mail is still handed to the delivery, which records it skipped")
    void issuedWithoutAnEmailTellsSales() {
        UUID sales = UUID.randomUUID();
        when(customers.portalAccount(customer)).thenReturn(Optional.empty());
        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.of(mailTo(null)));
        when(staff.activeHolders(EInvoiceIssuedNotificationListener.EMAIL_MISSING_AUDIENCE, null))
                .thenReturn(List.of(new StaffContact(sales, "sale@example.com")));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var notice = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(sales)), notice.capture());
        InboxMessage sent = notice.getValue();
        assertThat(sent.category()).isEqualTo("EINVOICE_BUYER_EMAIL_MISSING");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.title()).contains("SO-20261101-0007");
        assertThat(sent.body()).contains("EINV-20261112-0001", "SO-20261101-0007");
        assertThat(sent.link()).isEqualTo("/customers/" + customer);
        assertThat(sent.sourceType()).as("audit r2 M-1: Sales send the invoice on from the notice").isEqualTo("EINVOICE");
        assertThat(sent.sourceId()).isEqualTo(event.einvoiceId().toString());
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-buyer-email-missing:" + event.einvoiceId());
        verify(delivery).deliver(any());
    }

    @Test
    @DisplayName("audit r2 M-1 (HĐ-8 step 2): the invoice sent again at staff's request is a new mail, under its own "
            + "reference per request, addressed as invoicing says now (the e-mail Sales recorded), with its files; the "
            + "portal is not told again")
    void anInvoiceSentAgainIsANewMailToTheCurrentAddress() {
        Instant asked = Instant.parse("2026-11-13T02:00:00Z");
        com.stockflow.contracts.EInvoiceMailRequested again = new com.stockflow.contracts.EInvoiceMailRequested(
                event.einvoiceId(), event.code(), event.orderId(), event.orderNumber(), customer, asked,
                UUID.randomUUID());
        when(einvoices.mailAgain(event.einvoiceId())).thenReturn(Optional.of(mailTo("moi@trasua.example.com")));

        listener.on(again);

        var mail = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(delivery).deliver(mail.capture());
        CustomerMessage sent = mail.getValue();
        assertThat(sent.reference()).isEqualTo("customer-mail:einvoice.resent:" + event.einvoiceId() + ":"
                + asked.getEpochSecond());
        assertThat(sent.reference()).as("never the first mail's, which stays settled")
                .isNotEqualTo("customer-mail:einvoice.issued:" + event.einvoiceId());
        assertThat(sent.recipient().resolve()).hasValueSatisfying(contact ->
                assertThat(contact.email()).isEqualTo("moi@trasua.example.com"));
        assertThat(sent.attachments()).isNotNull();
        verify(einvoices, never()).mail(any());
        verify(inbox, never()).post(any(), any());
    }

    @Test
    @DisplayName("an invoice with a buyer e-mail tells Sales nothing")
    void anInvoiceWithAnEmailTellsSalesNothing() {
        when(customers.portalAccount(customer)).thenReturn(Optional.empty());
        when(einvoices.mail(event.einvoiceId())).thenReturn(Optional.of(mailTo("ke.toan@trasua.example.com")));

        listener.on(event);

        verify(staff, never()).activeHolders(any(), any());
        verify(inbox, never()).post(any(), any());
        verify(delivery).deliver(any());
    }
}
