package com.stockflow.invoicing.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The short transaction that puts a stored agreement on its invoice (WF-05 E4; TT 91 Điều 10 khoản 1 điểm b). A bean
 * of its own because the file is checked, scanned and stored <b>before</b> any transaction opens
 * ({@link EInvoiceCorrections#attachAgreement}): the invoice's row is locked only for the moment the key is written.
 */
@Component
class EInvoiceAgreements {

    private final EInvoiceRepository invoices;
    private final Clock clock;

    EInvoiceAgreements(EInvoiceRepository invoices, Clock clock) {
        this.invoices = invoices;
        this.clock = clock;
    }

    /**
     * The file is kept when this commits and deleted when it rolls back. One it replaces stays in the store: nothing
     * sweeps the e-invoice prefix, and a document both parties signed is not deleted on the strength of a later upload.
     */
    @Transactional
    public EInvoice.Snapshot attach(UUID id, long expectedVersion, String number, LocalDate date,
                                    PreparedFiles prepared) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (invoice.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
        }
        invoice.attachAgreement(prepared.single().file().key(), number, date, BusinessCalendar.date(clock.instant()));
        EInvoice.Snapshot saved = invoices.save(invoice).snapshot();
        prepared.adopt();
        return saved;
    }
}
