package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceMailRequested;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceErrorNoticeMail;
import com.stockflow.notification.api.EInvoiceMail;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.service.EmailContent.Fact;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Words the e-mail that carries an issued e-invoice to its buyer (WF-05 E5; plan §1.4): the invoice's number, date and
 * total, the tax authority's code, the code it is looked up by, and its PDF and XML attached.
 *
 * <p>Apart from {@link CustomerEmailComposer} because this message is not made of the event alone. The event names the
 * invoice; who it goes to, what it says and its files are read from invoicing ({@link EInvoiceDirectory}) when the
 * message is sent, and only then - asking which message an event calls for, as the retry job does for every pending
 * publication, reads nothing.</p>
 *
 * <p>It goes to the address <b>the invoice names</b> - the buyer's invoice e-mail, copied from the customer's legal
 * profile when the invoice was drawn up and what the accountant checked before issuing - not to the customer's contact
 * address, where order mail goes: the person who books invoices is rarely the one who places orders. An invoice issued
 * without one goes to the customer's invoice e-mail as it is now, and an invoice sent again at staff's request
 * ({@link #resent}) to that one first (audit r2 M-1): invoicing decides, {@link EInvoiceDirectory} says.</p>
 *
 * <p>It also words the notice that an issued invoice names its buyer wrongly (04/SS-HĐĐT): the same address, since it
 * is about that invoice, and no file - the invoice is not issued again, the buyer keeps the one it has.</p>
 */
@Component
class EInvoiceEmailComposer {

    static final String EINVOICE_ISSUED = "einvoice.issued";
    /**
     * The same message sent again at staff's request (audit r2 M-1): its own template code, so the log tells it from
     * the first, and under the e-invoice mail switch as the first is ({@code einvoice.} prefix); worded as the first.
     */
    static final String EINVOICE_RESENT = "einvoice.resent";
    static final String EINVOICE_ERROR_NOTICE = "einvoice.error-notice";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EInvoiceDirectory einvoices;
    private final CustomerEmailComposer wording;

    EInvoiceEmailComposer(EInvoiceDirectory einvoices, CustomerEmailComposer wording) {
        this.einvoices = einvoices;
        this.wording = wording;
    }

    /** One message per invoice ({@code customer-mail:einvoice.issued:<id>}), however often the event arrives. */
    Optional<CustomerMessage> issued(EInvoiceIssued event) {
        return Optional.of(new CustomerMessage(EINVOICE_ISSUED, event.einvoiceId().toString(), event.customerId(),
                null,
                addressee -> content(einvoices.mail(event.einvoiceId()).orElseThrow(), addressee),
                () -> einvoices.mail(event.einvoiceId()).map(mail ->
                        new CustomerContact(mail.customerId(), mail.buyerName(), mail.recipientEmail())),
                () -> einvoices.files(event.einvoiceId()).stream()
                        .map(file -> new Email.Attachment(file.name(), file.contentType(), file.content()))
                        .toList()));
    }

    /**
     * The invoice sent again at staff's request (audit r2 M-1; HĐ-8 "bổ sung e-mail → gửi lại"): a message of its own
     * per request ({@code customer-mail:einvoice.resent:<invoice id>:<requested at, epoch second>}) - the first one, a
     * mail settled as skipped for want of an address or sent to a wrong one, is never reopened, and a provider that keeps
     * idempotency keys would answer the first's key with the first's message. Addressed to the customer's invoice e-mail
     * as it is now ({@link EInvoiceDirectory#mailAgain}), with the same words and files as the first.
     */
    Optional<CustomerMessage> resent(EInvoiceMailRequested event) {
        return Optional.of(new CustomerMessage(EINVOICE_RESENT,
                event.einvoiceId() + ":" + event.requestedAt().getEpochSecond(), event.customerId(), null,
                addressee -> content(einvoices.mailAgain(event.einvoiceId()).orElseThrow(), addressee),
                () -> einvoices.mailAgain(event.einvoiceId()).map(mail ->
                        new CustomerContact(mail.customerId(), mail.buyerName(), mail.recipientEmail())),
                () -> einvoices.files(event.einvoiceId()).stream()
                        .map(file -> new Email.Attachment(file.name(), file.contentType(), file.content()))
                        .toList()));
    }

    /**
     * One message per notice ({@code customer-mail:einvoice.error-notice:<notice id>}), however often the event
     * arrives. Greets the buyer by the right name when the notice gives one.
     */
    Optional<CustomerMessage> errorNoticeRecorded(EInvoiceErrorNoticeRecorded event) {
        return Optional.of(new CustomerMessage(EINVOICE_ERROR_NOTICE, event.noticeId().toString(), event.customerId(),
                null,
                addressee -> content(einvoices.errorNotice(event.noticeId()).orElseThrow(), addressee),
                () -> einvoices.errorNotice(event.noticeId()).map(notice ->
                        new CustomerContact(notice.customerId(), notice.addresseeName(), notice.recipientEmail())),
                null));
    }

    private EmailContent content(EInvoiceErrorNoticeMail notice, String addressee) {
        ResourceBundle m = wording.bundle();
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact(m.getString("email.einvoice.fact.number"), notice.reference()));
        facts.add(new Fact(m.getString("email.einvoice.fact.date"), DAY.format(notice.issueDate())));
        facts.add(new Fact(m.getString("email.customer.fact.order"), notice.orderNumber()));
        if (notice.correctBuyerName() != null) {
            facts.add(new Fact(m.getString("email.einvoice.fact.correct-buyer-name"), notice.correctBuyerName()));
        }
        if (notice.correctBuyerAddress() != null) {
            facts.add(new Fact(m.getString("email.einvoice.fact.correct-buyer-address"),
                    notice.correctBuyerAddress()));
        }
        facts.add(new Fact(m.getString("email.einvoice.fact.error-reason"), notice.reason()));
        return wording.content(m, EINVOICE_ERROR_NOTICE, addressee, notice.reference(), facts, null,
                List.of(m.getString("email.einvoice.error-notice.kept").formatted(notice.formCode())));
    }

    private EmailContent content(EInvoiceMail mail, String addressee) {
        ResourceBundle m = wording.bundle();
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact(m.getString("email.einvoice.fact.number"), mail.reference()));
        facts.add(new Fact(m.getString("email.einvoice.fact.date"), DAY.format(mail.issueDate())));
        facts.add(new Fact(m.getString("email.customer.fact.order"), mail.orderNumber()));
        facts.add(new Fact(m.getString("email.einvoice.fact.total"),
                CustomerEmailComposer.money(mail.grandTotal(), mail.currency())));
        facts.add(new Fact(m.getString("email.einvoice.fact.tax-authority-code"), mail.taxAuthorityCode()));
        facts.add(new Fact(m.getString("email.einvoice.fact.lookup-code"), mail.lookupCode()));
        return wording.content(m, EINVOICE_ISSUED, addressee, mail.reference(), facts, null,
                List.of(m.getString("email.einvoice.issued.attached")));
    }
}
