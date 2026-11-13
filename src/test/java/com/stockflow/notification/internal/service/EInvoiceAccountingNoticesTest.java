package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceErrorNoticeMail;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 integration items b and c, notification side: the buyer told of a 04/SS notice that names it wrongly, and the
 * accountant told of a return whose adjustment e-invoice is left to be drawn up by hand.
 */
class EInvoiceAccountingNoticesTest {

    private static final Instant AT = Instant.parse("2026-11-12T03:00:00Z");

    @Nested
    @DisplayName("a 04/SS notice of a wrong buyer name or address")
    class ErrorNotice {

        private final CustomerDirectory customers = mock(CustomerDirectory.class);
        private final Inbox inbox = mock(Inbox.class);
        private final EInvoiceDirectory einvoices = mock(EInvoiceDirectory.class);
        private final CustomerEmailDelivery delivery = mock(CustomerEmailDelivery.class);
        private final EInvoiceErrorNoticeNotificationListener listener = new EInvoiceErrorNoticeNotificationListener(
                customers, inbox, new EInvoiceEmailComposer(einvoices, new CustomerEmailComposer(properties())),
                delivery);
        private final EInvoiceErrorNoticeRecorded event = new EInvoiceErrorNoticeRecorded(UUID.randomUUID(),
                UUID.randomUUID(), "EINV-20261112-0001", UUID.randomUUID(), "SO-20261101-0007", UUID.randomUUID(),
                AT);

        private CustomerMessage mailed() {
            var mail = ArgumentCaptor.forClass(CustomerMessage.class);
            verify(delivery).deliver(mail.capture());
            return mail.getValue();
        }

        @Test
        @DisplayName("the customer's portal account is told, and the buyer is mailed once per notice")
        void theBuyerIsToldOnThePortalAndByMail() {
            UUID account = UUID.randomUUID();
            when(customers.portalAccount(event.customerId())).thenReturn(Optional.of(account));

            listener.on(event);

            var posted = ArgumentCaptor.forClass(InboxMessage.class);
            verify(inbox).post(org.mockito.ArgumentMatchers.eq(List.of(account)), posted.capture());
            InboxMessage sent = posted.getValue();
            assertThat(sent.category()).isEqualTo("EINVOICE_ERROR_NOTICE");
            assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
            assertThat(sent.title()).isEqualTo("Hoá đơn điện tử của đơn SO-20261101-0007 có sai sót");
            assertThat(sent.link()).as("the customer portal's page").isEqualTo("/einvoices/" + event.einvoiceId());
            assertThat(sent.sourceId()).isEqualTo(event.einvoiceId().toString());
            assertThat(sent.dedupeKey()).isEqualTo("einvoice-error-notice:" + event.noticeId());

            CustomerMessage mail = mailed();
            assertThat(mail.reference()).isEqualTo("customer-mail:einvoice.error-notice:" + event.noticeId());
            assertThat(mail.customerId()).isEqualTo(event.customerId());
            assertThat(mail.attachments()).as("a notice carries no file").isNull();
        }

        @Test
        @DisplayName("the mail greets the buyer by the right name, not the wrong one the invoice carries")
        void greetedByTheRightName() {
            when(customers.portalAccount(any())).thenReturn(Optional.empty());
            when(einvoices.errorNotice(event.noticeId())).thenReturn(Optional.of(new EInvoiceErrorNoticeMail(
                    event.noticeId(), event.einvoiceId(), event.customerId(), "CONG TY TNHH IN LY AN PHAP",
                    "hoadon@example.com", "1C26TSB-00000042", "SO-20261101-0007", LocalDate.of(2026, 11, 12),
                    "04/SS-HDDT", "Sai mot chu trong ten", "CONG TY TNHH IN LY AN PHAT", null)));

            listener.on(event);

            verify(inbox, never()).post(any(), any());
            assertThat(mailed().recipient().resolve()).contains(new CustomerContact(event.customerId(),
                    "CONG TY TNHH IN LY AN PHAT", "hoadon@example.com"));
        }
    }

    @Nested
    @DisplayName("a return left without its adjustment e-invoice")
    class ReturnUnadjusted {

        private EInvoiceReturnUnadjusted event(EInvoiceReturnUnadjusted.Cause cause, String reasonCode) {
            return new EInvoiceReturnUnadjusted(UUID.randomUUID(), "RMA-20261112-0001", UUID.randomUUID(),
                    "SO-20261101-0007", cause, 2, 150, reasonCode, AT);
        }

        @Test
        @DisplayName("goods on no issued invoice: what is left, by pieces and order lines, once per return")
        void notOnAnIssuedInvoice() {
            EInvoiceReturnUnadjusted event = event(EInvoiceReturnUnadjusted.Cause.NOT_ON_AN_ISSUED_INVOICE, null);

            InboxMessage sent = EInvoiceReturnUnadjustedNotificationListener.message(event);

            assertThat(sent.category()).isEqualTo("EINVOICE_RETURN_UNADJUSTED");
            assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
            assertThat(sent.title()).isEqualTo(
                    "Phiếu trả hàng RMA-20261112-0001 cần lập hoá đơn điều chỉnh thủ công");
            assertThat(sent.body()).contains("RMA-20261112-0001", "SO-20261101-0007", "150 sản phẩm",
                    "trên 2 dòng đơn");
            assertThat(sent.link()).isEqualTo("/invoicing/einvoices?orderId=" + event.orderId());
            assertThat(sent.sourceType()).isEqualTo("RETURN_REQUEST");
            assertThat(sent.sourceId()).isEqualTo(event.returnRequestId().toString());
            assertThat(sent.dedupeKey()).isEqualTo("einvoice-return-unadjusted:" + event.returnRequestId());
        }

        @Test
        @DisplayName("a refusal names its code instead")
        void refused() {
            InboxMessage sent = EInvoiceReturnUnadjustedNotificationListener.message(
                    event(EInvoiceReturnUnadjusted.Cause.REFUSED, "EINVOICE_PROVIDER_CANNOT_CORRECT"));

            assertThat(sent.title()).isEqualTo(
                    "Chưa lập được hoá đơn điều chỉnh cho phiếu trả hàng RMA-20261112-0001");
            assertThat(sent.body()).contains("EINVOICE_PROVIDER_CANNOT_CORRECT").doesNotContain("null");
        }
    }
}
