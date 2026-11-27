package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.invoicing.api.EInvoiceListing;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The accountant's VAT e-invoice list ({@code GET /api/v1/einvoices}, WF-05 E2, kltn-docs 21): "hoá đơn nào còn chưa
 * phát hành?", "hoá đơn nào quá hạn xuất?", "đơn SO-… đã có hoá đơn chưa?", "hoá đơn tháng này của công ty ABC". Every
 * delivered shipment is invoiced (NĐ 254 Điều 9 khoản 3) and must be issued by the working day after delivery, so what
 * is still to issue, and what is late, is the accountant's daily queue.
 *
 * <p><b>Same rules as the screen.</b> {@code einvoices:READ} with scope ALL, and its controller refuses a portal
 * account ({@code ScopeGuards.requireBackOffice}), whose own issued invoices are read elsewhere; so does this tool. The
 * rows come in the screen's order through {@link EInvoiceListing}, which reads the same table.</p>
 *
 * <p><b>The buyer</b> is named as the invoice names it, which the screen shows to everyone who may read invoices, and
 * as a {@link CustomerName}, so the model provider sees the conversation's alias rather than the company's name
 * (ADR-0018). The buyer's tax code and address stay out: the invoice holds them for the tax authority, and no question
 * about where an invoice stands needs them. A customer named in the question is resolved by {@link CustomerMatch}, as
 * the order and price tools do: a caller who may list customers is offered the names a fragment matches, one who may
 * not names the customer exactly or by tax code.</p>
 *
 * <p><b>Overdue is the screen's</b>: not issued, not voided, and the deadline reached at the moment of the call; the
 * OVERDUE filter and each row's flag use one instant, so a row can never be listed as overdue and flagged as not.</p>
 */
@Component
class FindEInvoicesTool implements AssistantTool {

    /**
     * Fewer than the api's {@value EInvoiceListing#MAX_INVOICES}: a row carries three amounts, three moments and the
     * buyer's name (whole, for an MCP client), and twenty of the longest still fit well under the result limit, where
     * fifty would not.
     */
    static final int MAX_ROWS = 20;

    /** TO_ISSUE, OVERDUE and ANY, then every status of the api, so the two cannot drift apart. */
    static final List<String> STATUSES = Stream.concat(Stream.of("TO_ISSUE", "OVERDUE", "ANY"),
            EInvoiceListing.STATUSES.stream()).toList();

    private static final ToolSpec SPEC = new ToolSpec("findEInvoices",
            "VAT e-invoices (hoá đơn điện tử), one per delivered shipment plus corrections, those still to issue "
                    + "first (nearest deadline first), then the newest. Each row: code (the invoice's code in "
                    + "StockFlow), type (ORIGINAL for a delivered shipment; ADJUSTMENT or REPLACEMENT correcting an "
                    + "issued invoice), status, adjusted (an adjustment of it was issued; it stays in force), overdue "
                    + "(not issued yet and its deadline, the working day after delivery, is reached), orderNumber, "
                    + "shipmentCode (the delivery it invoices), contractNumber, buyer, issueDate (the invoice's date, "
                    + "the day of delivery), issueDueAt (the deadline to issue), series and invoiceNumber (given by "
                    + "the provider on issue; absent before), grandTotal with VAT, taxTotal the VAT, subtotal "
                    + "before it, currency, issuedAt, and rejectionCode for a refused one. status: DRAFT drawn up, "
                    + "not sent; SUBMITTING sent, waiting for the provider's answer; ISSUED numbered and signed, never "
                    + "changed; REJECTED refused by the provider or the tax authority, to correct and issue again; "
                    + "VOIDED_DRAFT a draft given up; REPLACED an issued invoice another one replaced. TO_ISSUE asks "
                    + "for DRAFT, SUBMITTING and REJECTED together; OVERDUE for those of them whose deadline is "
                    + "reached. An order with no row may not be delivered yet: say only that no invoice was found. "
                    + "Filter by status, by the order number, by the invoice date (issuedFrom, issuedTo, Vietnam "
                    + "calendar) and by customer with its name as registered, part of it, its tax code or an alias "
                    + "(KH#3) a tool gave: when the text names no customer exactly, no invoices come back but "
                    + "candidates (to ask which one) or a note (relay it). total counts every match; when it is above "
                    + "the invoices returned (truncated), say how many were left out. Issuing, voiding and correcting "
                    + "are done on the e-invoice screen.",
            ToolSchema.object()
                    .choice("status", "Only invoices in this status; TO_ISSUE for every one still to issue, OVERDUE "
                            + "for those past their deadline, ANY for every status", STATUSES)
                    .string("orderNumber", "A customer order number exactly as shown on screen, for its invoices; an "
                            + "empty string for every order")
                    .string("customer", "Only this customer's invoices: its name, part of it, its tax code or an alias "
                            + "such as KH#3; an empty string for every customer")
                    .string("issuedFrom", "First invoice date, YYYY-MM-DD; an empty string for no first day")
                    .string("issuedTo", "Last invoice date, YYYY-MM-DD; an empty string for no last day")
                    .integer("limit", "How many invoices to return, 1 to " + MAX_ROWS)
                    .build());

    private final EInvoiceListing invoices;
    private final CurrentUserProvider currentUsers;
    private final CustomerService customers;
    private final Clock clock;

    FindEInvoicesTool(EInvoiceListing invoices, CurrentUserProvider currentUsers, CustomerService customers,
                      Clock clock) {
        this.invoices = invoices;
        this.currentUsers = currentUsers;
        this.customers = customers;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/einvoices";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.EINVOICES, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        Optional<CurrentUser> user = currentUsers.current();
        user.ifPresent(ScopeGuards::requireBackOffice);
        boolean mayListCustomers = user.map(caller -> caller.can(ToolPermissions.CUSTOMERS, Action.READ))
                .orElse(false);
        String status = arguments.choice("status", STATUSES);
        String orderNumber = arguments.optionalText("orderNumber", 32);
        String typedCustomer = arguments.optionalText("customer", 200);
        LocalDate issuedFrom = ToolDates.optional(arguments, "issuedFrom");
        LocalDate issuedTo = ToolDates.optional(arguments, "issuedTo");
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        CustomerMatch customer = null;
        if (typedCustomer != null) {
            customer = CustomerMatch.find(customers, typedCustomer, mayListCustomers, arguments.pseudonyms());
            if (!customer.found()) {
                // No customer was chosen, so nothing was counted: null, never a 0 the model would read as "none".
                return new Answer(List.of(), null, null, customer.note(), null, customer.candidates());
            }
        }
        Instant now = clock.instant();
        List<String> statuses = switch (status) {
            case "TO_ISSUE", "OVERDUE" -> EInvoiceListing.TO_ISSUE_STATUSES;
            case "ANY" -> List.of();
            default -> List.of(status);
        };
        PageResponse<EInvoiceListing.EInvoiceRow> page = invoices.einvoices(new EInvoiceListing.Filter(statuses,
                orderNumber, customer == null ? null : customer.customerId(), issuedFrom, issuedTo,
                "OVERDUE".equals(status) ? now : null), now, limit);
        List<Row> rows = page.items().stream().map(Row::of).toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size(), null,
                customer == null ? null : customer.customerName(), List.of());
    }

    /**
     * @param total      every invoice that matches; null when the text named no customer, so nothing was looked for
     * @param truncated  {@code total} is above the invoices returned; null with {@code total}
     * @param note       why no customer was chosen; null otherwise
     * @param customer   the customer whose invoices these are, when the question named one
     * @param candidates the customers the text matched when it named none exactly ({@link CustomerMatch})
     */
    record Answer(List<Row> einvoices, Long total, Boolean truncated, String note, CustomerName customer,
                  List<CustomerMatch.Candidate> candidates) {
    }

    /**
     * @param overdue       not issued, not voided, and the deadline reached when the tool ran
     * @param buyer         the buyer as the invoice names it, written as the conversation's alias for the model
     * @param series        the form's template digit and the symbol together, as printed ({@code 1C26TAA}); null
     *                      before the invoice is issued, with {@code invoiceNumber}
     * @param rejectionCode why it was refused; null unless REJECTED
     */
    record Row(String code, String type, String status, boolean adjusted, boolean overdue, String orderNumber,
               String shipmentCode, String contractNumber, CustomerName buyer, LocalDate issueDate,
               Instant issueDueAt, String series, Integer invoiceNumber, BigDecimal grandTotal, BigDecimal taxTotal,
               BigDecimal subtotal, String currency, Instant issuedAt, String rejectionCode) {

        static Row of(EInvoiceListing.EInvoiceRow row) {
            UUID buyer = row.customerId();
            return new Row(row.code(), row.type(), row.status(), row.adjusted(), row.overdue(), row.orderNumber(),
                    row.shipmentCode(), row.contractNumber(),
                    buyer == null && row.buyerName() == null ? null : new CustomerName(buyer, row.buyerName()),
                    row.issueDate(), row.issueDueAt(),
                    row.symbol() == null ? null : (row.templateCode() == null ? "" : row.templateCode()) + row.symbol(),
                    row.invoiceNumber(), row.grandTotal(), row.taxTotal(), row.subtotal(), row.currency(),
                    row.issuedAt(), row.rejectionCode());
        }
    }
}
