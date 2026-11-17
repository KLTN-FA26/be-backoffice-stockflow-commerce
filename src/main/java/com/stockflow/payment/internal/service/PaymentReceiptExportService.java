package com.stockflow.payment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.export.ExportFile;
import com.stockflow.common.export.ExportLabels;
import com.stockflow.common.export.XlsxTable;
import com.stockflow.common.export.XlsxTable.Column;
import com.stockflow.common.export.XlsxTable.Kind;
import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import com.stockflow.payment.internal.repository.ReceiptExportSearch;
import com.stockflow.payment.internal.repository.ReceiptExportSearch.ExportedLine;
import com.stockflow.payment.internal.repository.ReceiptExportSearch.ExportedReceipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * What the accountant takes out of the payment receipts (2026-11-14 plan, BE2-A): the total of a filter, and the
 * receipts of a filter as an Excel file. The system keeps no books of money (15 §0, BR-06): the accounting software
 * does, and this file is how the receipts recorded here reach it - the accountant imports them there and checks them
 * against the bank statement. The e-invoice register's CSV is the other half (invoicing).
 *
 * <p>Both use the list's own filter ({@link ReceiptFilter}): the total above the list, the rows of the list and the rows
 * of the file are one set of receipts.</p>
 */
@Service
@Transactional(readOnly = true)
public class PaymentReceiptExportService {

    /**
     * What the RECORDED receipts of a filter add up to. A receipt is in one currency and every price is in VND
     * ({@code ck_customer_price_currency}), so there is one currency in practice; {@code byCurrency} says so rather than
     * assuming it, and {@code totalAmount} / {@code currency} are null should there ever be two.
     */
    public record ReceiptSummary(long count, BigDecimal totalAmount, String currency, List<CurrencyTotal> byCurrency) {
    }

    /** How many RECORDED receipts in one currency, and their sum. */
    public record CurrencyTotal(String currency, long count, BigDecimal amount) {
    }

    private static final String DEFAULT_CURRENCY = "VND";

    private final InstallmentSearch search;
    private final ReceiptExportSearch exports;
    private final CustomerSummaries customers;
    private final IdentityService identity;
    private final Messages messages;
    private final Clock clock;

    PaymentReceiptExportService(InstallmentSearch search, ReceiptExportSearch exports, CustomerSummaries customers,
                                IdentityService identity, Messages messages, Clock clock) {
        this.search = search;
        this.exports = exports;
        this.customers = customers;
        this.identity = identity;
        this.messages = messages;
        this.clock = clock;
    }

    /** The count and the sum of the RECORDED receipts {@code filter} matches; a voided receipt counts no money. */
    public ReceiptSummary summary(ReceiptFilter filter) {
        List<CurrencyTotal> totals = search.recordedTotals(filter).stream()
                .map(total -> new CurrencyTotal(total.currency(), total.count(), total.amount())).toList();
        long count = totals.stream().mapToLong(CurrencyTotal::count).sum();
        if (totals.isEmpty()) {
            return new ReceiptSummary(0, BigDecimal.ZERO, DEFAULT_CURRENCY, List.of());
        }
        if (totals.size() > 1) {
            return new ReceiptSummary(count, null, null, totals);
        }
        return new ReceiptSummary(count, totals.getFirst().amount(), totals.getFirst().currency(), totals);
    }

    /**
     * The receipts of {@code filter} as XLSX, two sheets: the receipts (day received, statement reference, customer,
     * amount, status, who recorded it, note, void) and their lines (the contract, the order and the installment each
     * paid). Voided receipts are in it unless the filter leaves them out: the accounting software needs to know a
     * receipt it may have imported was voided (15 §4.4).
     *
     * <p>Read in one snapshot ({@code REPEATABLE_READ}): the receipts and their lines are two statements, and under the
     * default READ COMMITTED a receipt voided or recorded between them would be RECORDED on one sheet and VOIDED, or
     * missing, on the other - in the file the accountant imports.</p>
     *
     * @throws BusinessException {@code EXPORT_TOO_LARGE} past {@link XlsxTable#MAX_ROWS} receipts or lines - nothing is
     *                           read beyond one row over
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Auditable(action = AuditAction.EXPORT, resourceType = "payment-receipt",
            details = "{customerId: #filter.customerId(), status: #filter.status(), reference: #filter.reference(), "
                    + "receivedFrom: #filter.receivedFrom(), receivedTo: #filter.receivedTo()}")
    public ExportFile export(ReceiptFilter filter) {
        List<ExportedReceipt> receipts = exports.receipts(filter, XlsxTable.MAX_ROWS + 1);
        List<ExportedLine> lines = exports.lines(filter, XlsxTable.MAX_ROWS + 1);
        if (receipts.size() > XlsxTable.MAX_ROWS || lines.size() > XlsxTable.MAX_ROWS) {
            throw new BusinessException(ErrorCode.EXPORT_TOO_LARGE, "The filter matches more than %d receipts or lines"
                    .formatted(XlsxTable.MAX_ROWS));
        }
        Set<UUID> customerIds = new HashSet<>();
        receipts.forEach(receipt -> customerIds.add(receipt.customerId()));
        if (filter.customerId() != null) {
            customerIds.add(filter.customerId());
        }
        Map<UUID, CustomerSummary> customer = customers.summariesOf(customerIds);
        Map<UUID, String> people = identity.displayNames(receipts.stream()
                .flatMap(receipt -> Stream.of(receipt.recordedBy(), receipt.voidedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList());

        Instant now = clock.instant();
        List<String> titles = List.of(label("title", "Payment receipts"), filterLine(filter, customer),
                ExportLabels.exportedAt(messages, now));
        List<Column<ExportedReceipt>> receiptColumns = List.of(
                new Column<>(label("column.received-on", "Received on"), Kind.DATE, ExportedReceipt::receivedOn),
                new Column<>(label("column.reference", "Statement reference"), Kind.TEXT, ExportedReceipt::reference),
                new Column<>(label("column.customer-code", "Customer code"), Kind.TEXT,
                        r -> code(customer.get(r.customerId()))),
                new Column<>(label("column.customer-name", "Customer"), Kind.TEXT,
                        r -> name(customer.get(r.customerId()))),
                new Column<>(label("column.amount", "Amount"), Kind.MONEY, ExportedReceipt::amount),
                new Column<>(label("column.currency", "Currency"), Kind.TEXT, ExportedReceipt::currency),
                new Column<>(label("column.status", "Status"), Kind.TEXT, ExportedReceipt::status),
                new Column<>(label("column.recorded-by", "Recorded by"), Kind.TEXT, r -> person(people, r.recordedBy())),
                new Column<>(label("column.recorded-at", "Recorded at"), Kind.DATETIME, ExportedReceipt::recordedAt),
                new Column<>(label("column.note", "Note"), Kind.TEXT, ExportedReceipt::note),
                new Column<>(label("column.voided-by", "Voided by"), Kind.TEXT, r -> person(people, r.voidedBy())),
                new Column<>(label("column.voided-at", "Voided at"), Kind.DATETIME, ExportedReceipt::voidedAt),
                new Column<>(label("column.void-reason", "Void reason"), Kind.TEXT, ExportedReceipt::voidReason));
        List<Column<ExportedLine>> lineColumns = List.of(
                new Column<>(label("column.reference", "Statement reference"), Kind.TEXT, ExportedLine::reference),
                new Column<>(label("column.received-on", "Received on"), Kind.DATE, ExportedLine::receivedOn),
                new Column<>(label("column.status", "Status"), Kind.TEXT, ExportedLine::receiptStatus),
                new Column<>(label("column.contract", "Contract"), Kind.TEXT, ExportedLine::contractNumber),
                new Column<>(label("column.order", "Order"), Kind.TEXT, ExportedLine::orderNumber),
                new Column<>(label("column.installment", "Installment"), Kind.TEXT,
                        line -> line.seq() + " - " + line.installmentName()),
                new Column<>(label("column.milestone", "Milestone"), Kind.TEXT, ExportedLine::milestone),
                new Column<>(label("column.amount", "Amount"), Kind.MONEY, ExportedLine::amount),
                new Column<>(label("column.currency", "Currency"), Kind.TEXT, ExportedLine::currency));
        byte[] file = XlsxTable.write(List.of(
                new XlsxTable.Sheet<>(label("sheet.receipts", "Receipts"), titles, receiptColumns, receipts),
                new XlsxTable.Sheet<>(label("sheet.lines", "Lines"), titles, lineColumns, lines)));
        return new ExportFile(ExportLabels.filename("payment-receipts", now), file);
    }

    private String filterLine(ReceiptFilter filter, Map<UUID, CustomerSummary> customer) {
        List<String> parts = new ArrayList<>();
        if (filter.receivedFrom() != null) {
            parts.add(label("filter.received-from", "received from {0}", ExportLabels.day(filter.receivedFrom())));
        }
        if (filter.receivedTo() != null) {
            parts.add(label("filter.received-to", "received to {0}", ExportLabels.day(filter.receivedTo())));
        }
        if (filter.customerId() != null) {
            CustomerSummary summary = customer.get(filter.customerId());
            parts.add(label("filter.customer", "customer {0}", summary == null ? filter.customerId().toString()
                    : summary.code() + " " + summary.fullName()));
        }
        if (filter.status() != null) {
            parts.add(label("filter.status", "status {0}", filter.status().name()));
        }
        if (filter.reference() != null) {
            parts.add(label("filter.reference", "statement reference {0}", filter.reference()));
        }
        return ExportLabels.filterLine(messages, parts);
    }

    private String label(String key, String fallback, Object... arguments) {
        return messages.get("export.payment-receipts." + key, fallback, arguments);
    }

    private static String code(CustomerSummary customer) {
        return customer == null ? null : customer.code();
    }

    private static String name(CustomerSummary customer) {
        return customer == null ? null : customer.fullName();
    }

    private static String person(Map<UUID, String> people, UUID id) {
        return id == null ? null : people.getOrDefault(id, id.toString());
    }
}
