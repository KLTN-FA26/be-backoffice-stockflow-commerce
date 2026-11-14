package com.stockflow.reporting.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.export.ExportFile;
import com.stockflow.common.export.ExportLabels;
import com.stockflow.common.export.XlsxTable;
import com.stockflow.common.export.XlsxTable.Column;
import com.stockflow.common.export.XlsxTable.Kind;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.Receivables;
import com.stockflow.reporting.internal.domain.ReceivableLine;
import com.stockflow.reporting.internal.domain.ReceivableTotals;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The receivables report (kltn-docs 15 §3 "công nợ phải thu và vị thế tín dụng", §4.3 step 8; 17 §4.11 "báo cáo
 * khác"; 2026-11-14 plan, BE2-B): one row per customer that owes anything after delivery, aged by due date, beside
 * their credit limit and what is left of it. It is the credit control view - who owes, how late, how close to the limit
 * - not the accounting books: an opening and closing balance per period is the accounting software's (15 BR-06), so
 * there is none here.
 *
 * <p><b>Today only, deliberately.</b> The payment module keeps what each installment still owes, not a ledger of
 * payments (15 §0), so the report says what is owed now and how old it is today; "as of an earlier day" cannot be
 * worked out and is not offered. Today is the Asia/Ho_Chi_Minh business day.</p>
 *
 * <p>Beside the 15 §4.3 figures, which stay as 15 defines them, each row and the totals carry what is overdue on
 * installments due on signing ({@link ReceivableLine#signingOverdue()}): 15 §4.3 does not count those as receivable,
 * but the credit check of a new contract refuses on them, and a report that left them out would show such a customer
 * clean. "Overdue only" keeps them for the same reason.</p>
 *
 * <p>The amounts are the payment module's ({@link Receivables}, one statement), the names and credit the customer
 * module's ({@link CustomerSummaries}, {@link CustomerCredits}, one statement each). The customers who owe are few, so
 * the rows are filtered, sorted and paged here, in memory, and the totals are over every row the filter keeps, not just
 * the page shown.</p>
 */
@Service
@Transactional(readOnly = true)
public class ReceivablesReportService {

    /** Sortable by what is overdue (the default: the most overdue first), what is owed, the name, the oldest debt. */
    static final SortWhitelist SORT = SortWhitelist.of("overdue", "receivable", "customerName", "oldestDueDate")
            .withDefault("overdue", Sort.Direction.DESC);

    /** Names in Vietnamese order (Collator compares are synchronized, so one instance serves every request). */
    private static final Comparator<Object> NAMES = Collator.getInstance(Locale.forLanguageTag("vi"));

    /** A page of rows, the totals of every row the filter keeps, and the day the figures are of. */
    public record ReceivablesReport(LocalDate asOf, PageResponse<ReceivableLine> rows, List<ReceivableTotals> totals) {
    }

    private final Receivables receivables;
    private final CustomerSummaries customers;
    private final CustomerCredits credits;
    private final Messages messages;
    private final Clock clock;

    ReceivablesReportService(Receivables receivables, CustomerSummaries customers, CustomerCredits credits,
                             Messages messages, Clock clock) {
        this.receivables = receivables;
        this.customers = customers;
        this.credits = credits;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * @param customerId  one customer, or null for every customer that owes something
     * @param overdueOnly keep only the customers with something overdue - receivable, or due on signing
     *                    ({@link ReceivableLine#hasOverdue()})
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for an unknown {@code customerId};
     *                           {@code UNSUPPORTED_PARAMETER} for a bad page or size; {@code VALIDATION_FAILED} for a
     *                           sort outside the list
     */
    public ReceivablesReport report(UUID customerId, boolean overdueOnly, int page, int size, String sort) {
        Pageable pageable = Pages.of(page, size, SORT.parse(sort));
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<ReceivableLine> lines = lines(today, customerId, overdueOnly, pageable.getSort());
        int from = (int) Math.min(pageable.getOffset(), lines.size());
        int to = Math.min(from + pageable.getPageSize(), lines.size());
        return new ReceivablesReport(today, PageResponse.of(lines.subList(from, to), page, size, lines.size()),
                ReceivableTotals.of(lines));
    }

    /**
     * The report as XLSX: every row the filter keeps, in the order asked, and a total row per currency. Audited as an
     * EXPORT: it takes every customer's debt out of the building.
     */
    @Auditable(action = AuditAction.EXPORT, resourceType = "receivables-report",
            details = "{customerId: #customerId, overdueOnly: #overdueOnly, sort: #sort}")
    public ExportFile export(UUID customerId, boolean overdueOnly, String sort) {
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);
        List<ReceivableLine> lines = lines(today, customerId, overdueOnly, SORT.parse(sort));
        List<ReceivableLine> rows = new ArrayList<>(lines);
        for (ReceivableTotals total : ReceivableTotals.of(lines)) {
            rows.add(new ReceivableLine(null, null, label("total", "Total"), total.currency(), total.receivable(),
                    total.notYetDue(), total.overdue1To30(), total.overdue31To60(), total.overdue61To90(),
                    total.overdueOver90(), total.committedNotReached(), total.signingOverdue(), null, null, null,
                    0));
        }
        List<String> parts = new ArrayList<>();
        if (customerId != null) {
            CustomerSummary named = customers.summariesOf(List.of(customerId)).get(customerId);
            parts.add(label("filter.customer", "customer {0}", named == null ? customerId.toString()
                    : named.code() + " " + named.fullName()));
        }
        if (overdueOnly) {
            parts.add(label("filter.overdue-only", "customers with something overdue only"));
        }
        List<String> titles = List.of(label("title", "Receivables by customer, aged by due date"),
                label("as-of", "As of {0}", ExportLabels.day(today)), ExportLabels.filterLine(messages, parts),
                ExportLabels.exportedAt(messages, now));
        List<Column<ReceivableLine>> columns = List.of(
                new Column<>(label("column.customer-code", "Customer code"), Kind.TEXT, ReceivableLine::customerCode),
                new Column<>(label("column.customer-name", "Customer"), Kind.TEXT, ReceivableLine::customerName),
                new Column<>(label("column.currency", "Currency"), Kind.TEXT, ReceivableLine::currency),
                new Column<>(label("column.receivable", "Receivable"), Kind.MONEY, ReceivableLine::receivable),
                new Column<>(label("column.not-yet-due", "Not yet due"), Kind.MONEY, ReceivableLine::notYetDue),
                new Column<>(label("column.overdue-1-30", "1-30 days overdue"), Kind.MONEY,
                        ReceivableLine::overdue1To30),
                new Column<>(label("column.overdue-31-60", "31-60 days overdue"), Kind.MONEY,
                        ReceivableLine::overdue31To60),
                new Column<>(label("column.overdue-61-90", "61-90 days overdue"), Kind.MONEY,
                        ReceivableLine::overdue61To90),
                new Column<>(label("column.overdue-over-90", "Over 90 days overdue"), Kind.MONEY,
                        ReceivableLine::overdueOver90),
                new Column<>(label("column.overdue", "Overdue in all"), Kind.MONEY, ReceivableLine::overdue),
                new Column<>(label("column.signing-overdue", "Overdue on signing (not receivable)"), Kind.MONEY,
                        ReceivableLine::signingOverdue),
                new Column<>(label("column.committed-not-reached", "Signed, milestone not reached"), Kind.MONEY,
                        ReceivableLine::committedNotReached),
                new Column<>(label("column.exposure", "Credit exposure"), Kind.MONEY, ReceivableLine::exposure),
                new Column<>(label("column.credit-limit", "Credit limit"), Kind.MONEY, ReceivableLine::creditLimit),
                new Column<>(label("column.credit-term-days", "Credit term (days)"), Kind.INTEGER,
                        ReceivableLine::creditTermDays),
                new Column<>(label("column.available", "Credit left"), Kind.MONEY, ReceivableLine::available),
                new Column<>(label("column.oldest-due-date", "Oldest due date"), Kind.DATE,
                        ReceivableLine::oldestDueDate),
                new Column<>(label("column.open-items", "Open items"), Kind.INTEGER,
                        line -> line.customerId() == null ? null : line.openItems()));
        byte[] file = XlsxTable.write(label("sheet", "Receivables"), titles, columns, rows);
        return new ExportFile(ExportLabels.filename("receivables", now), file);
    }

    private List<ReceivableLine> lines(LocalDate today, UUID customerId, boolean overdueOnly, Sort sort) {
        List<CustomerReceivable> owed = receivables.receivables(today, customerId);
        Set<UUID> ids = owed.stream().map(CustomerReceivable::customerId)
                .collect(Collectors.toCollection(HashSet::new));
        if (customerId != null) {
            ids.add(customerId);
        }
        Map<UUID, CustomerSummary> summaries = customers.summariesOf(ids);
        if (customerId != null && !summaries.containsKey(customerId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId);
        }
        Map<UUID, CreditTerms> terms = credits.creditTermsOf(ids);
        return owed.stream()
                .map(row -> line(row, summaries.get(row.customerId()), terms.get(row.customerId())))
                .filter(line -> !overdueOnly || line.hasOverdue())
                .sorted(comparator(sort))
                .toList();
    }

    private static ReceivableLine line(CustomerReceivable row, CustomerSummary customer, CreditTerms terms) {
        return new ReceivableLine(row.customerId(), customer == null ? null : customer.code(),
                customer == null ? null : customer.fullName(), row.currency(), row.receivable(), row.notYetDue(),
                row.overdue1To30(), row.overdue31To60(), row.overdue61To90(), row.overdueOver90(),
                row.committedNotReached(), row.signingOverdue(), terms == null ? null : terms.creditLimit(),
                terms == null ? null : terms.creditTermDays(), row.oldestDueDate(), row.openItems());
    }

    /**
     * The order asked, empty values last whichever way, then the name and the id so that two rows equal on the key
     * keep one order from page to page.
     */
    static Comparator<ReceivableLine> comparator(Sort sort) {
        Comparator<ReceivableLine> order = (a, b) -> 0;
        for (Sort.Order by : sort) {
            Comparator<ReceivableLine> one = switch (by.getProperty()) {
                case "overdue" -> key(ReceivableLine::overdue, Comparator.naturalOrder(), by.isDescending());
                case "receivable" -> key(ReceivableLine::receivable, Comparator.naturalOrder(), by.isDescending());
                case "customerName" -> key(ReceivableLine::customerName, NAMES, by.isDescending());
                case "oldestDueDate" -> key(ReceivableLine::oldestDueDate, Comparator.naturalOrder(),
                        by.isDescending());
                default -> throw new IllegalStateException("Not a sort of this report: " + by.getProperty());
            };
            order = order.thenComparing(one);
        }
        return order.thenComparing(key(ReceivableLine::customerName, NAMES, false))
                .thenComparing(ReceivableLine::customerId)
                .thenComparing(ReceivableLine::currency);
    }

    private static <K> Comparator<ReceivableLine> key(Function<ReceivableLine, K> key, Comparator<? super K> natural,
                                                      boolean descending) {
        Comparator<? super K> direction = descending ? natural.reversed() : natural;
        return Comparator.comparing(key, Comparator.nullsLast(direction));
    }

    private String label(String key, String fallback, Object... arguments) {
        return messages.get("export.receivables." + key, fallback, arguments);
    }
}
