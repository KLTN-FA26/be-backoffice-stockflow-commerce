package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.payment.api.OverdueCustomer;
import com.stockflow.payment.api.OverdueReceivables;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The collection list: customers owing past their due dates - "khách nào đang nợ quá hạn", "ai nợ quá hạn lâu nhất",
 * "tổng nợ quá hạn bao nhiêu" - for the accountant, Sales and the coordinator (P8).
 *
 * <p><b>The receivables screen, summed per customer.</b> {@code GET /api/v1/receivables}, whose guard this tool
 * carries (scope ALL, back office only, as that handler checks: a portal customer holds the same READ for its own
 * receivables), with status OVERDUE and collector CUSTOMER, read through {@code payment :: api}. OVERDUE is the status
 * the nightly job gives a receivable the day after its due date, the same rows {@code getCustomerCredit} sums, so a
 * customer listed here is one whose next credit order goes on hold for the credit approver (BR-PAY-008). What a carrier
 * collects on delivery is not the customer's debt and is not counted.</p>
 *
 * <p><b>Named only for who may list customers.</b> The receivables screen shows a customer's id, not its name, and the
 * roles that read receivables without {@code customer-customers:READ} (the accountant, the coordinator) cannot list
 * customers anywhere: a list of debtors by name would be the customer list their grants leave out, the rule
 * {@link CustomerMatch} keeps. So a customer is named only for a caller who may list customers (Sales); every row
 * carries the orders its overdue receivables are for, which the receivables screen shows, so the others can follow
 * one up by its order. Should the accountant see debtors by name, granting {@code customer-customers:READ} is the
 * switch, and nothing here changes. Nothing else of the customer: no contact, no tax code (design §5.5).</p>
 */
@Component
class FindOverdueCustomersTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    /** The orders a row names: enough to follow one up, few enough that fifty rows stay small. */
    static final int ORDER_NUMBERS = 3;

    static final List<String> ORDERS = List.of("LARGEST_FIRST", "OLDEST_FIRST");

    private static final ToolSpec SPEC = new ToolSpec("findOverdueCustomers",
            "Customers who owe on receivables past their due date, the collection list, one row per customer: "
                    + "overdueAmount (what is still unpaid on those receivables, in currency), oldestDueDate and "
                    + "daysOverdue (since the oldest of them, in Vietnam), how many receivables are overdue, and "
                    + "orderNumbers, the first orders they are for. customerName is given only to a user who may list "
                    + "customers; for anyone else it is absent, so name the customer by its orders and never guess "
                    + "a name. totalCustomers counts every customer owing past due, totalOverdue sums what they owe "
                    + "by currency. A receivable becomes overdue the day after its due date (the delivery day plus "
                    + "the days to pay agreed on the order) and stays so until it is paid in full; what a carrier "
                    + "collects on delivery is not counted. A customer listed here gets no new credit: its next credit "
                    + "order goes on hold for the credit approver (BR-PAY-008). Order LARGEST_FIRST by the amount, or "
                    + "OLDEST_FIRST by the oldest due date. When truncated is true, say how many customers were left "
                    + "out. For one customer's whole position, terms and credit left, use getCustomerCredit.",
            ToolSchema.object()
                    .choice("order", "LARGEST_FIRST for the largest amount first, OLDEST_FIRST for the longest "
                            + "overdue first", ORDERS)
                    .integer("limit", "How many customers to return, 1 to " + MAX_ROWS)
                    .build());

    private final OverdueReceivables receivables;
    private final CustomerService customers;
    private final CurrentUserProvider currentUsers;
    private final Clock clock;

    FindOverdueCustomersTool(OverdueReceivables receivables, CustomerService customers,
                             CurrentUserProvider currentUsers, Clock clock) {
        this.receivables = receivables;
        this.customers = customers;
        this.currentUsers = currentUsers;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/receivables";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public Answer run(ToolArguments arguments) {
        // The receivables screen's own check: the same READ lets a portal customer see its own receivables only.
        CurrentUser user = currentUsers.current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        ScopeGuards.requireBackOffice(user);
        boolean oldestFirst = "OLDEST_FIRST".equals(arguments.choice("order", ORDERS));
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        OverdueReceivables.OverdueCustomers overdue = receivables.overdueCustomers(oldestFirst, limit);
        boolean named = user.can(ToolPermissions.CUSTOMERS, Action.READ);
        Map<java.util.UUID, String> names = named ? customers.fullNames(overdue.customers().stream()
                .map(OverdueCustomer::customerId).distinct().toList()) : Map.of();
        LocalDate today = BusinessDate.of(clock.instant());
        List<Row> rows = overdue.customers().stream().map(customer -> new Row(names.get(customer.customerId()),
                customer.overdueAmount(), customer.currency(), customer.oldestDueDate(),
                customer.oldestDueDate() == null ? null
                        : Math.max(0, ChronoUnit.DAYS.between(customer.oldestDueDate(), today)),
                customer.receivables(), orders(customer.orderNumbers()))).toList();
        List<Total> totals = overdue.amountByCurrency().entrySet().stream()
                .map(entry -> new Total(entry.getKey(), entry.getValue())).toList();
        return new Answer(rows, overdue.total(), totals, overdue.rows() > rows.size(), named);
    }

    /**
     * @param customers      the first {@code limit}, one row per customer and currency
     * @param totalCustomers every customer owing past due
     * @param totalOverdue   what they all owe past due, by currency
     * @param truncated      more owe past due than are listed
     * @param customersNamed the user may list customers, so rows carry names
     */
    record Answer(List<Row> customers, long totalCustomers, List<Total> totalOverdue, boolean truncated,
                  boolean customersNamed) {
    }

    /**
     * @param customerName only for a user who may list customers; null otherwise, and when the record is gone
     * @param orderNumbers the first {@value FindOverdueCustomersTool#ORDER_NUMBERS} orders the overdue receivables are
     *                     for, earliest due first, comma-separated: one text, so the panel's table shows it too
     */
    record Row(String customerName, BigDecimal overdueAmount, String currency, LocalDate oldestDueDate,
               Long daysOverdue, int overdueReceivables, String orderNumbers) {
    }

    /** The first orders, and how many more there are. */
    static String orders(List<String> numbers) {
        if (numbers.isEmpty()) {
            return null;
        }
        String first = String.join(", ", numbers.subList(0, Math.min(ORDER_NUMBERS, numbers.size())));
        return numbers.size() > ORDER_NUMBERS ? first + " (+" + (numbers.size() - ORDER_NUMBERS) + ")" : first;
    }

    record Total(String currency, BigDecimal amount) {
    }
}
