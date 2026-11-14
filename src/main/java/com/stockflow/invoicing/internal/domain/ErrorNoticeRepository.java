package com.stockflow.invoicing.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: the 04/SS-HĐĐT notices of issued invoices ({@code invoicing.einvoice_error_notice}). */
public interface ErrorNoticeRepository {

    void insert(ErrorNotice notice);

    Optional<ErrorNotice> findById(UUID id);

    /** The notice with its row locked until the transaction ends: two sendings of one notice are decided in turn. */
    Optional<ErrorNotice> findByIdForUpdate(UUID id);

    /** Writes the notice's status and the answer it got. */
    void update(ErrorNotice notice);

    /** An invoice's notices, oldest first. */
    List<ErrorNotice.Snapshot> findByInvoice(UUID einvoiceId);

    /** Whether a notice of the invoice is still DRAFT or SENT ({@code uk_einvoice_error_notice_open}). */
    boolean hasOpen(UUID einvoiceId);
}
