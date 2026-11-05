package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.ReturnQueries;
import com.stockflow.order.api.ReturnQueries.ReturnSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Customers' returns (design §4.2, C10) - "Yêu cầu đổi trả nào đang chờ duyệt?", "Hàng trả nào sắp hết hạn gửi về?",
 * "Đổi trả của khách ABC tới đâu rồi?" - as the back-office returns screen lists them ({@code GET /api/v1/returns}),
 * through {@link ReturnQueries}, which reads the list that screen reads. Its rules are therefore the screen's: back
 * office only ({@code ScopeGuards.requireBackOffice}, since a customer holds {@code sales-rmas:READ} for their own
 * returns and this list is everyone's), and staff limited to warehouses see the returns of orders their warehouses
 * ship.
 *
 * <p><b>Deadline.</b> An approved return waits for the goods until its deadline and then expires. The order stores
 * the deadline as the start of the first day too late ({@code ReturnPolicy.endOfDays}), so the last day the goods may
 * come is the day before: {@code lastDayToReturn}, and the days left to it, are worked out here on today's date in
 * Vietnam, so "sắp hết hạn" is answered by sorting, not by the model comparing dates, and never a day too late.</p>
 *
 * <p><b>Customer</b>, as in {@code findOrders}, only for a caller who may list customers
 * ({@code customer-customers:READ}): the warehouse, QC and accounting read returns but have no customer screen, and a
 * filter by name or tax code would tell them which companies are customers, and their names behind a tax code. They
 * filter by RMA or order number, or by an alias ({@code KH#3}) an earlier answer already showed them; rows name their
 * customer only for those who may list customers. A row carries no customer-typed text: the return's description and
 * the photos stay on the screen.</p>
 */
@Component
class FindReturnsTool implements AssistantTool {

    static final int MAX_ROWS = ReturnQueries.MAX_RETURNS;

    static final List<String> STATUSES = Stream.concat(Stream.of("OPEN", "ANY"), ReturnQueries.STATUSES.stream())
            .toList();

    static final List<String> ORDERS = List.of("NEWEST_FIRST", "DEADLINE_FIRST");

    private static final ToolSpec SPEC = new ToolSpec("findReturns",
            "Customers' return requests (RMA), the newest first or the nearest return deadline first: RMA number, "
                    + "order number, status, reason, what the customer asked for, where it was opened, when it was "
                    + "requested, lastDayToReturn and daysToDeadline, the settlement amounts, when it was closed. "
                    + "status: REQUESTED waiting for Sales to review it, AWAITING_RETURN approved and waiting for the "
                    + "customer to send the goods back by the deadline, RECEIVED counted into quarantine and waiting "
                    + "for QC, INSPECTED every unit accepted or rejected (by QC, or on the photos when the goods stay "
                    + "with the customer) and its settlement still to be proposed, "
                    + "SETTLEMENT_PENDING a settlement waiting for a second person, RESOLVED made good, REJECTED "
                    + "refused, CANCELLED withdrawn, EXPIRED the goods did not come back by the deadline. OPEN asks "
                    + "for the first five together. reason: PRINT_DEFECT, DESIGN_MISMATCH, DAMAGED_IN_TRANSIT, "
                    + "WRONG_ITEM, SHORT_DELIVERY, CHANGED_MIND or OTHER; requestedResolution: REPLACEMENT, REFUND or "
                    + "CREDIT_NOTE. lastDayToReturn is the last day the goods may come back, and daysToDeadline the "
                    + "days left to it (0: today is the last day, below 0: past), for AWAITING_RETURN only, worked out "
                    + "here: quote them. order DEADLINE_FIRST with status AWAITING_RETURN lists the returns whose "
                    + "deadline is nearest. Filter by text in the RMA or order number, and by customer: its name as "
                    + "registered, part of it, its tax code or an alias (KH#3) a tool gave, for a user who may list "
                    + "customers; anyone else filters by an alias only. When the customer is not found, the result has "
                    + "no rows, no total and a note or candidates instead: never say there are no returns. A row names "
                    + "its customer only for a user who may list customers. Only returns of "
                    + "orders the user's warehouses ship, for a user limited to warehouses. total counts every match; "
                    + "when it is above the rows returned (truncated), say how many were left out.",
            ToolSchema.object()
                    .choice("status", "Only returns in this status; OPEN for every status still being worked on; "
                            + "ANY for every status", STATUSES)
                    .string("customer", "Only this customer's returns: its name, part of it, its tax code or an "
                            + "alias such as KH#3; an empty string for every customer")
                    .string("number", "Text in the RMA number or the order number; an empty string for any")
                    .choice("order", "NEWEST_FIRST, or DEADLINE_FIRST for the nearest return deadline first",
                            ORDERS)
                    .integer("limit", "How many returns to return, 1 to " + MAX_ROWS)
                    .build());

    private final ReturnQueries returns;
    private final CurrentUserProvider currentUsers;
    private final CustomerService customers;
    private final Clock clock;

    FindReturnsTool(ReturnQueries returns, CurrentUserProvider currentUsers, CustomerService customers, Clock clock) {
        this.returns = returns;
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
        return "GET /api/v1/returns";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.RMAS, action = Action.READ, scope = DataScope.ALL)
    public Answer run(ToolArguments arguments) {
        Optional<CurrentUser> user = currentUsers.current();
        user.ifPresent(ScopeGuards::requireBackOffice);
        boolean mayListCustomers = user.map(caller -> caller.can(ToolPermissions.CUSTOMERS, Action.READ))
                .orElse(false);
        String status = arguments.choice("status", STATUSES);
        String typedCustomer = arguments.optionalText("customer", 200);
        String number = arguments.optionalText("number", 40);
        boolean deadlineFirst = "DEADLINE_FIRST".equals(arguments.choice("order", ORDERS));
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        CustomerMatch customer = null;
        if (typedCustomer != null) {
            if (!mayListCustomers && !Pseudonyms.looksLikeAlias(typedCustomer)) {
                // The same answer whether such a customer exists or not: no screen of theirs says either.
                return new Answer(List.of(), null, null, "Returns are filtered by a customer's name or tax code only "
                        + "for users who may list customers (customer-customers:READ); give the RMA or order number "
                        + "instead.", null, List.of());
            }
            customer = CustomerMatch.find(customers, typedCustomer, mayListCustomers, arguments.pseudonyms());
            if (!customer.found()) {
                // Nothing was counted: null, never a 0 the model would read as "no returns".
                return new Answer(List.of(), null, null, customer.note(), null, customer.candidates());
            }
        }
        List<String> statuses = switch (status) {
            case "OPEN" -> ReturnQueries.OPEN_STATUSES;
            case "ANY" -> List.of();
            default -> List.of(status);
        };
        PageResponse<ReturnSummary> page = returns.returns(statuses,
                customer == null ? null : customer.customerId(), number, deadlineFirst, limit);
        Map<UUID, String> names = names(page.items(), mayListCustomers);
        LocalDate today = BusinessDate.of(clock.instant());
        List<Row> rows = page.items().stream().map(found -> Row.of(found, names, today)).toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size(), null,
                customer == null || !mayListCustomers ? null : customer.customerName(), List.of());
    }

    /** The customers' names, read once for the page, for a caller who may list customers; none for anyone else. */
    private Map<UUID, String> names(List<ReturnSummary> found, boolean mayListCustomers) {
        if (!mayListCustomers || found.isEmpty()) {
            return Map.of();
        }
        return customers.fullNames(found.stream().map(ReturnSummary::customerId).filter(Objects::nonNull)
                .distinct().toList());
    }

    /**
     * @param total      every return that matches; above the returns listed, some were left out. Null when no
     *                   customer was chosen, so no return was looked for
     * @param note       why no customer was chosen; null otherwise
     * @param customer   the customer whose returns these are, when the question named one and the user may list
     *                   customers
     * @param candidates the customers the text matched when it named none exactly ({@link CustomerMatch})
     */
    record Answer(List<Row> returns, Long total, Boolean truncated, String note, CustomerName customer,
                  List<CustomerMatch.Candidate> candidates) {
    }

    /**
     * @param lastDayToReturn the last day the approved goods may come back: the day before the stored deadline, which is
     *                        the start of the first day too late; null before approval or for goods that stay
     * @param daysToDeadline  whole days from today to {@code lastDayToReturn}, for AWAITING_RETURN only: 0 is today,
     *                        below 0 already past (the return expires shortly); null otherwise
     * @param customer        the customer, for a caller who may list customers; null otherwise
     */
    record Row(String rmaNumber, String orderNumber, String status, String reason, String requestedResolution,
               String channel, Instant requestedAt, LocalDate lastDayToReturn, Long daysToDeadline,
               BigDecimal settlementAmount, BigDecimal refundedAmount, BigDecimal creditedAmount, String currency,
               Instant closedAt, CustomerName customer) {

        static Row of(ReturnSummary found, Map<UUID, String> names, LocalDate today) {
            LocalDate lastDay = found.returnDeadline() == null ? null
                    : BusinessDate.of(found.returnDeadline().minusNanos(1));
            Long daysToDeadline = "AWAITING_RETURN".equals(found.status()) && lastDay != null
                    ? ChronoUnit.DAYS.between(today, lastDay) : null;
            String name = found.customerId() == null ? null : names.get(found.customerId());
            return new Row(found.rmaNumber(), found.orderNumber(), found.status(), found.reason(),
                    found.requestedResolution(), found.channel(), found.requestedAt(), lastDay,
                    daysToDeadline, found.settlementAmount(), found.refundedAmount(), found.creditedAmount(),
                    found.currency(), found.closedAt(),
                    name == null ? null : new CustomerName(found.customerId(), name));
        }
    }
}
