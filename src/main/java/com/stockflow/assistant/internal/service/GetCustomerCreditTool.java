package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CustomerCredit;
import com.stockflow.order.api.CustomerCreditQueries;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * ROLE-8: one customer's commercial terms and what it owes - "Công nợ hiện tại của khách ABC?", "Khách ABC có nợ quá
 * hạn không, từ ngày nào?", "Khách ABC được mua chịu bao nhiêu ngày?" - for the accountant, Sales and the coordinator.
 *
 * <p><b>Two screens, each under its own permission.</b> The terms are the commercial terms screen's
 * ({@code GET /api/v1/customers/{customerId}/credit-profile}, {@code CustomerService.creditTerms}), whose guard this
 * tool carries. What is owed is the receivables screen's ({@code GET /api/v1/receivables}), summed by
 * {@code PaymentService.creditPosition}, so {@code payment-receivables:READ} is declared by {@link #alsoRequires} and
 * {@code run} checks that very list. Under the seeded grants the two go together, so nobody is turned away who holds
 * one; the declaration keeps it so the day they part.</p>
 *
 * <p><b>Credit left, from the module that counts it</b> (P8). What a customer may still buy on credit is its limit
 * minus its exposure, and the exposure also counts credit orders placed and not yet billed, which only the order module
 * counts. The limit minus the receivables here would overstate it: a limit of 100M with 30M owed and 50M on
 * undelivered credit orders leaves 20M, not 70M, and a salesperson who promises the 70M sends the next order to the
 * credit-hold queue. So the figure comes from {@code order :: api} ({@code CustomerCreditQueries}), the very number
 * {@code GET /api/v1/orders/credit-position/{customerId}} shows and the checkout's credit check uses, under the same
 * guard as this tool's; the description forbids working it out from the other figures (design §16, ROLE-8).</p>
 *
 * <p><b>What is left out.</b> Who approved the terms and the approver's note (staff free text), and of the customer
 * everything but its name and account status (design §5.5). The customer is named as people name one
 * ({@link CustomerMatch}): the accountant and the coordinator, who may not list customers, name it exactly.</p>
 */
@Component
class GetCustomerCreditTool implements AssistantTool {

    /** What the receivables screen shows, summed here; {@link #run} checks exactly this list. */
    private static final List<PermissionCode> RECEIVABLES = List.of(
            PermissionCode.of(ToolPermissions.RECEIVABLES, Action.READ));

    private static final ToolSpec SPEC = new ToolSpec("getCustomerCredit",
            "One customer's commercial terms and what it owes now. terms: allowedTerms are the payment terms it may "
                    + "be sold on (PREPAID pays before, DEPOSIT pays a share first, CREDIT pays after delivery), "
                    + "defaultTerm the one used unless another is chosen, depositPercent the deposit share, "
                    + "depositBalancePolicy how a deposit order's balance is collected (BEFORE_DELIVERY, ON_DELIVERY "
                    + "by the carrier, ON_CREDIT as a receivable), creditLimit and creditTermDays the credit limit and "
                    + "the days it has to pay a credit order; configured false means nobody has set terms and the "
                    + "customer prepays. receivables: outstanding is what the customer still owes on the receivables "
                    + "it pays itself (goods delivered on credit, deposit balances put on credit, cancellation fees), "
                    + "hasOverdue and overdueAmount the part past its due date, oldestOverdueDueDate the earliest due "
                    + "date among those; a balance a carrier collects on delivery is not in it. credit: exposure is "
                    + "everything counted against the limit, the receivables owed plus credit orders placed and not "
                    + "yet billed; availableCredit is creditLimit minus exposure, never below zero, what the next "
                    + "credit order may come to without going on hold; both null when the customer may not buy on "
                    + "credit. Give availableCredit as returned and never work it out from creditLimit and "
                    + "outstanding, which leave the unbilled orders out. While hasOverdue is true the customer gets "
                    + "no new credit, whatever availableCredit says: a "
                    + "credit order goes on hold for the credit approver and none is released without them "
                    + "(BR-PAY-008); say so when the question is about selling on credit. The customer is named as "
                    + "the user named it: the company name, "
                    + "whole or in part, or its tax code. When it does not name exactly one customer, nothing is "
                    + "returned but a note saying what to ask, with candidates by name when there are any: list them "
                    + "and ask which. It returns no contact details, no tax code, and nothing of who approved the "
                    + "terms or why.",
            ToolSchema.object()
                    .string("customer", "The customer as the user named it, every word kept: the company name whole "
                            + "or in part, words such as Công ty, Cửa hàng or Khách hàng included, since they can be "
                            + "part of the registered name; or its tax code")
                    .build());

    private final CustomerService customers;
    private final PaymentService payments;
    private final CustomerCreditQueries credit;
    private final CurrentUserProvider currentUsers;

    GetCustomerCreditTool(CustomerService customers, PaymentService payments, CustomerCreditQueries credit,
                          CurrentUserProvider currentUsers) {
        this.customers = customers;
        this.payments = payments;
        this.credit = credit;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/customers/{customerId}/credit-profile";
    }

    @Override
    public List<PermissionCode> alsoRequires() {
        return RECEIVABLES;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.CREDIT_PROFILES, action = Action.READ, scope = DataScope.ALL)
    public Answer run(ToolArguments arguments) {
        CurrentUser user = currentUsers.current()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // The receivables list is the back office's (ReceivableController): a portal account reads only its own.
        ScopeGuards.requireBackOffice(user);
        alsoRequires().forEach(permission -> user.require(permission.resource(), permission.action()));
        CustomerMatch customer = CustomerMatch.find(customers, arguments.text("customer", 200),
                user.can(ToolPermissions.CUSTOMERS, Action.READ));
        if (!customer.found()) {
            return new Answer(null, null, null, null, null, customer.candidates(), customer.note());
        }
        return new Answer(customer.name(), customer.status(), Terms.of(customers.creditTerms(customer.customerId())),
                Receivables.of(payments.creditPosition(customer.customerId())),
                Credit.of(credit.creditOf(customer.customerId())), List.of(), null);
    }

    /**
     * @param terms       null when no customer was chosen
     * @param receivables null when no customer was chosen
     * @param credit      null when no customer was chosen
     * @param candidates  the customers the text matched, when it named none exactly
     * @param note        why no customer was chosen
     */
    record Answer(String customerName, String customerStatus, Terms terms, Receivables receivables, Credit credit,
                  List<CustomerMatch.Candidate> candidates, String note) {
    }

    /**
     * What is used of the credit limit and what is left, as the checkout's credit check counts it (BR-PAY-005).
     *
     * @param exposure        the receivables owed plus credit orders placed and not yet billed, less what was paid on
     *                        them; null when the customer may not buy on credit
     * @param availableCredit {@code creditLimit} less {@code exposure}, never below zero; null likewise
     */
    record Credit(BigDecimal exposure, BigDecimal availableCredit) {

        static Credit of(CustomerCredit position) {
            return position.creditAllowed() ? new Credit(position.exposure(), position.availableCredit())
                    : new Credit(null, null);
        }
    }

    /**
     * The commercial terms as the terms screen shows them, without who approved them and their note.
     *
     * @param configured false when nobody has set terms: the customer prepays
     * @param approvedAt when the terms were last set; null when they never were
     */
    record Terms(boolean configured, List<String> allowedTerms, String defaultTerm, BigDecimal depositPercent,
                 String depositBalancePolicy, BigDecimal creditLimit, Integer creditTermDays, String currency,
                 Instant approvedAt) {

        static Terms of(CreditTerms terms) {
            return new Terms(terms.configured(),
                    Arrays.stream(CommercialTerm.values()).filter(terms::allows).map(Enum::name).toList(),
                    terms.defaultTerm() == null ? null : terms.defaultTerm().name(), terms.depositPercent(),
                    terms.depositBalancePolicy() == null ? null : terms.depositBalancePolicy().name(),
                    terms.creditLimit(), terms.creditTermDays(), terms.currency(), terms.approvedAt());
        }
    }

    /**
     * What the customer owes on the receivables it pays itself, as the receivables screen would sum it.
     *
     * @param outstanding          unpaid on every such receivable not yet paid in full
     * @param overdueAmount        the part of it on receivables past their due date; zero when none
     * @param oldestOverdueDueDate the earliest due date among those; null when nothing is overdue
     */
    record Receivables(BigDecimal outstanding, boolean hasOverdue, BigDecimal overdueAmount,
                       LocalDate oldestOverdueDueDate) {

        static Receivables of(CreditPosition position) {
            return new Receivables(position.outstandingReceivables(), position.hasOverdue(),
                    position.overdueAmount(), position.oldestOverdueDueDate());
        }
    }
}
