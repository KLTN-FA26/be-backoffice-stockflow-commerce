package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.EInvoiceDirectory;
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
 * address, where order mail goes: the person who books invoices is rarely the one who places orders.</p>
 */
@Component
class EInvoiceEmailComposer {

    static final String EINVOICE_ISSUED = "einvoice.issued";

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
