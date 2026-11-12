package com.stockflow.invoicing.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * What is read off e-invoices: the accountant's list, one invoice with its calls to the provider, the links to an
 * issued invoice's files, the register of a period (plan §1.4 "bảng kê hoá đơn"), and the customer's own issued
 * invoices on the portal (WF-05 E5).
 */
@Service
@Transactional(readOnly = true)
public class EInvoiceQueryService {

    /** The files of an issued invoice. */
    public enum FileKind { XML, PDF }

    /** A period's register may not span more than this: a tax year and its neighbours' edges. */
    static final int MAX_REGISTER_DAYS = 400;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EInvoiceRepository invoices;
    private final EInvoiceQueries queries;
    private final ProviderCallLog calls;
    private final FileTransfers files;

    EInvoiceQueryService(EInvoiceRepository invoices, EInvoiceQueries queries, ProviderCallLog calls,
                         FileTransfers files) {
        this.invoices = invoices;
        this.queries = queries;
        this.calls = calls;
        this.files = files;
    }

    public PageResponse<EInvoiceQueries.Listing> list(EInvoiceQueries.Filter filter, int page, int size) {
        // Pages checks the bounds; the order is the query's own (what is still to issue first).
        var pageable = Pages.of(page, size);
        return PageResponse.of(queries.list(filter, (int) pageable.getOffset(), pageable.getPageSize()), page, size,
                queries.count(filter));
    }

    /** @throws BusinessException {@code EINVOICE_NOT_FOUND} */
    public EInvoice.Snapshot get(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
    }

    /** The invoice's calls to the provider, oldest first. */
    public List<ProviderCallLog.Call> calls(UUID id) {
        get(id);
        return calls.of(id);
    }

    /**
     * A short-lived link to an issued invoice's XML or PDF, saved under the invoice's number.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_NOT_ISSUED} for one that has no files yet
     */
    public DownloadLink fileLink(UUID id, FileKind kind) {
        EInvoice.Snapshot invoice = get(id);
        if (!invoice.status().wasIssued()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_ISSUED,
                    "Invoice %s is %s and has no files".formatted(invoice.code(), invoice.status()));
        }
        String name = "%s%s-%08d".formatted(invoice.templateCode(), invoice.symbol(), invoice.invoiceNumber());
        return kind == FileKind.XML ? files.downloadLink(invoice.xmlFileKey(), name + ".xml")
                : files.downloadLink(invoice.pdfFileKey(), name + ".pdf");
    }

    /** The adjustments and the replacement of an invoice, voided ones too, oldest first. */
    public List<EInvoiceQueries.Listing> corrections(UUID id) {
        get(id);
        return queries.correctionsOf(id);
    }

    /**
     * A short-lived link to the signed agreement attached to a correction (TT 91 Điều 10 khoản 1 điểm b: kept, and
     * shown when asked for).
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for an invoice
     *         with no agreement attached
     */
    public DownloadLink agreementLink(UUID id) {
        EInvoice.Snapshot invoice = get(id);
        if (invoice.agreementFileKey() == null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s has no agreement attached".formatted(invoice.code()));
        }
        return files.downloadLink(invoice.agreementFileKey(), "agreement-%s.pdf".formatted(invoice.code()));
    }

    /** The invoices issued to one customer, the newest invoice date first: the portal's list. */
    public PageResponse<EInvoiceQueries.Listing> issuedTo(UUID customerId, int page, int size) {
        var pageable = Pages.of(page, size);
        return PageResponse.of(queries.issuedTo(customerId, (int) pageable.getOffset(), pageable.getPageSize()),
                page, size, queries.countIssuedTo(customerId));
    }

    /**
     * A short-lived link to the XML or PDF of an invoice issued to {@code customerId}.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND} for another customer's invoice, one not issued, and no
     *         invoice alike: the customer learns nothing about invoices that are not its own - not even that one
     *         exists, which a 403 or a 409 would say
     */
    public DownloadLink customerFileLink(UUID customerId, UUID id, FileKind kind) {
        if (queries.findIssuedTo(customerId, id).isEmpty()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_FOUND);
        }
        return fileLink(id, kind);
    }

    /**
     * Every invoice issued with a date in the period, by symbol and number.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a period that ends before it starts or is longer than
     *         {@value #MAX_REGISTER_DAYS} days
     */
    public List<EInvoiceQueries.RegisterEntry> register(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(MAX_REGISTER_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "from, to: a period of at most %d days, from no later than to".formatted(MAX_REGISTER_DAYS));
        }
        return queries.register(from, to);
    }

    /**
     * {@link #register} as CSV: UTF-8 with a byte-order mark so a spreadsheet reads the Vietnamese names, one header
     * line, one line per invoice. Bulk data leaving the system, so it is audited.
     */
    @Auditable(action = AuditAction.EXPORT, resourceType = "einvoice-register")
    public String registerCsv(LocalDate from, LocalDate to) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("template_code,symbol,invoice_number,issue_date,type,status,adjusted,buyer_name,buyer_tax_code,"
                + "buyer_address,order_number,amount_before_tax,tax_amount,total_amount,tax_authority_code,"
                + "lookup_code,original_invoice\r\n");
        for (EInvoiceQueries.RegisterEntry entry : register(from, to)) {
            csv.append(String.join(",", cell(entry.templateCode()), cell(entry.symbol()),
                    "%08d".formatted(entry.invoiceNumber()), DAY.format(entry.issueDate()), entry.type().name(),
                    entry.status().name(), Boolean.toString(entry.adjusted()), cell(entry.buyerName()),
                    cell(entry.buyerTaxCode()), cell(entry.buyerAddress()), cell(entry.orderNumber()),
                    money(entry.subtotal()), money(entry.taxTotal()), money(entry.grandTotal()),
                    cell(entry.taxAuthorityCode()), cell(entry.lookupCode()), cell(entry.originalReference())))
                    .append("\r\n");
        }
        return csv.toString();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(0, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * A text cell, always quoted. A value a spreadsheet would run as a formula - one starting with {@code = + - @}, a
     * tab or a carriage return - is led by an apostrophe: a buyer's name is typed by people, and a register is opened
     * in a spreadsheet by the accountant.
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
