package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceMail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers notification's question about an issued invoice (WF-05 E5, {@link EInvoiceDirectory}): what the message to
 * the buyer says, where it goes and the two files it carries. Notification declares the interface because it depends
 * on no business module; see the interface for the argument.
 *
 * <p>Everything comes from the invoice as issued, which no longer changes: the buyer and the address copied onto it
 * when it was drawn up, the number and codes the provider gave, and the files stored - and locked - when it was
 * issued. So every attempt at one message reads the same thing, which the mail provider's idempotency key needs.</p>
 *
 * <p>An invoice that was never issued answers nothing: a draft is not sent to anybody. One since replaced still
 * answers - the message about it may be late, and the buyer was issued that invoice.</p>
 *
 * <p>No transaction around the files: they are read from the object store, which may take its time, and a database
 * connection must not be held while it does.</p>
 */
@Component
class EInvoiceDirectoryAdapter implements EInvoiceDirectory {

    private final EInvoiceRepository invoices;
    private final FileStorage storage;

    EInvoiceDirectoryAdapter(EInvoiceRepository invoices, FileStorage storage) {
        this.invoices = invoices;
        this.storage = storage;
    }

    @Override
    public Optional<EInvoiceMail> mail(UUID einvoiceId) {
        return issued(einvoiceId).map(invoice -> new EInvoiceMail(invoice.id(), invoice.customerId(),
                invoice.buyer().name(), invoice.buyer().email(), reference(invoice), invoice.orderNumber(),
                invoice.issueDate(), invoice.grandTotal(), invoice.currency(), invoice.taxAuthorityCode(),
                invoice.lookupCode()));
    }

    @Override
    public List<EInvoiceMail.File> files(UUID einvoiceId) {
        Optional<EInvoice.Snapshot> found = issued(einvoiceId);
        if (found.isEmpty()) {
            return List.of();
        }
        EInvoice.Snapshot invoice = found.get();
        String name = reference(invoice);
        return List.of(new EInvoiceMail.File(name + ".pdf", "application/pdf", read(invoice.pdfFileKey())),
                new EInvoiceMail.File(name + ".xml", "application/xml", read(invoice.xmlFileKey())));
    }

    private Optional<EInvoice.Snapshot> issued(UUID einvoiceId) {
        return einvoiceId == null ? Optional.empty() : invoices.findById(einvoiceId).map(EInvoice::snapshot)
                .filter(invoice -> invoice.status().wasIssued());
    }

    /** The invoice as people quote it and as its files are named: template code, symbol and the 8-digit number. */
    private static String reference(EInvoice.Snapshot invoice) {
        return "%s%s-%08d".formatted(invoice.templateCode(), invoice.symbol(), invoice.invoiceNumber());
    }

    private byte[] read(String key) {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new StorageException("Could not read an e-invoice file", failure);
        }
    }
}
