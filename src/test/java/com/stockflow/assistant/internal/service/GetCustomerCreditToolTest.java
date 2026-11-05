package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * getCustomerCredit (ROLE-8): the terms and what is owed, field by field, and the credit left as the order module
 * counts it, never one worked out from the receivables alone, which would overstate it.
 */
class GetCustomerCreditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final String APPROVER_NOTE = "Duyệt theo đề nghị của anh Nam, gọi 0903 123 456";
    private static final CustomerSummary ABC = new CustomerSummary(UUID.randomUUID(), null, "Công ty In Ly ABC",
            "ke-toan@abc.example.vn", "0903123456", "0312345678", null, "ACTIVE", 0, Instant.now());

    private final CustomerService customers = mock(CustomerService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final com.stockflow.order.api.CustomerCreditQueries credit =
            mock(com.stockflow.order.api.CustomerCreditQueries.class);
    private final GetCustomerCreditTool tool = new GetCustomerCreditTool(customers, payments, credit, users);

    @BeforeEach
    void signedIn() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ACCOUNTANT).scope(DataScope.ALL)
                .can("customer-credit-profiles", Action.READ).can("payment-receivables", Action.READ).build()));
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(ABC), 0, CustomerMatch.SEARCHED, 1));
        // 100 million on 30 days; 45 million owed, 15 million of it overdue since 14/10.
        when(customers.creditTerms(ABC.customerId())).thenReturn(new CreditTerms(ABC.customerId(), true, true, true,
                true, CommercialTerm.CREDIT, new BigDecimal("30.00"), new BigDecimal("100000000.0000"), 30, "VND",
                APPROVER, Instant.parse("2026-10-01T02:00:00Z"), APPROVER_NOTE, 4, DepositBalancePolicy.ON_CREDIT));
        when(payments.creditPosition(ABC.customerId())).thenReturn(new CreditPosition(new BigDecimal("45000000"),
                true, new BigDecimal("15000000"), LocalDate.of(2026, 10, 14)));
        // 45 million owed and 35 million on credit orders not billed yet: 80 used, 20 left, not 55.
        when(credit.creditOf(ABC.customerId())).thenReturn(new com.stockflow.order.api.CustomerCredit(true,
                new BigDecimal("100000000.0000"), new BigDecimal("80000000"), new BigDecimal("20000000"), "VND",
                true));
    }

    private static ToolArguments arguments(String customer) {
        return ToolArguments.parse(JSON, "{\"customer\":\"" + customer + "\"}");
    }

    @Test
    @DisplayName("the terms as the terms screen shows them and the receivables as the receivables screen sums them")
    void termsAndReceivables() {
        GetCustomerCreditTool.Answer answer = tool.run(arguments("0312345678"));

        assertThat(answer.customerName().name()).isEqualTo("Công ty In Ly ABC");
        assertThat(answer.customerStatus()).isEqualTo("ACTIVE");
        GetCustomerCreditTool.Terms terms = answer.terms();
        assertThat(terms.configured()).isTrue();
        assertThat(terms.allowedTerms()).containsExactly("PREPAID", "DEPOSIT", "CREDIT");
        assertThat(terms.defaultTerm()).isEqualTo("CREDIT");
        assertThat(terms.depositPercent()).isEqualByComparingTo("30");
        assertThat(terms.depositBalancePolicy()).isEqualTo("ON_CREDIT");
        assertThat(terms.creditLimit()).isEqualByComparingTo("100000000");
        assertThat(terms.creditTermDays()).isEqualTo(30);
        assertThat(terms.currency()).isEqualTo("VND");
        assertThat(terms.approvedAt()).isEqualTo(Instant.parse("2026-10-01T02:00:00Z"));
        GetCustomerCreditTool.Receivables owed = answer.receivables();
        assertThat(owed.outstanding()).isEqualByComparingTo("45000000");
        assertThat(owed.hasOverdue()).isTrue();
        assertThat(owed.overdueAmount()).isEqualByComparingTo("15000000");
        assertThat(owed.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(answer.note()).isNull();
    }

    @Test
    @DisplayName("the credit left is the order module's, unbilled credit orders counted: 20 million, never the 55 the "
            + "limit less the receivables would give; the description forbids working it out")
    void creditLeftAsTheOrderModuleCountsIt() throws Exception {
        GetCustomerCreditTool.Answer answer = tool.run(arguments("Công ty In Ly ABC"));
        String sent = JSON.writeValueAsString(answer);

        assertThat(answer.credit().exposure()).isEqualByComparingTo("80000000");
        assertThat(answer.credit().availableCredit()).isEqualByComparingTo("20000000");
        assertThat(sent).doesNotContain("55000000");
        assertThat(tool.spec().description()).contains("credit orders placed and not yet billed",
                "never work it out from creditLimit and outstanding", "whatever availableCredit says");
    }

    @Test
    @DisplayName("a customer who may not buy on credit has no exposure or credit left to show")
    void noCreditNoFigures() {
        when(credit.creditOf(ABC.customerId())).thenReturn(new com.stockflow.order.api.CustomerCredit(false, null,
                BigDecimal.ZERO, null, "VND", false));

        GetCustomerCreditTool.Answer answer = tool.run(arguments("Công ty In Ly ABC"));

        assertThat(answer.credit().exposure()).isNull();
        assertThat(answer.credit().availableCredit()).isNull();
    }

    @Test
    @DisplayName("neither who approved the terms nor their note, nor any contact detail of the customer, leaves")
    void nothingPersonal() throws Exception {
        String sent = JSON.writeValueAsString(tool.run(arguments("Công ty In Ly ABC")));

        assertThat(sent).doesNotContain(APPROVER.toString(), "anh Nam", "0903", "ke-toan@abc.example.vn",
                "0312345678", ABC.customerId().toString());
    }

    @Test
    @DisplayName("a customer nobody has set terms for prepays: configured false, PREPAID only, nothing owed")
    void noTermsSet() {
        when(customers.creditTerms(ABC.customerId())).thenReturn(CreditTerms.prepaidOnly(ABC.customerId()));
        when(payments.creditPosition(ABC.customerId())).thenReturn(new CreditPosition(BigDecimal.ZERO, false));

        GetCustomerCreditTool.Answer answer = tool.run(arguments("Công ty In Ly ABC"));

        assertThat(answer.terms().configured()).isFalse();
        assertThat(answer.terms().allowedTerms()).containsExactly("PREPAID");
        assertThat(answer.terms().creditLimit()).isNull();
        assertThat(answer.terms().approvedAt()).isNull();
        assertThat(answer.receivables().hasOverdue()).isFalse();
        assertThat(answer.receivables().oldestOverdueDueDate()).isNull();
    }

    @Test
    @DisplayName("without payment-receivables:READ the tool refuses, naming it, before any customer is looked up")
    void receivablesAreTheirOwnPermission() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ACCOUNTANT).scope(DataScope.ALL)
                .can("customer-credit-profiles", Action.READ).build()));

        assertThatThrownBy(() -> tool.run(arguments("Công ty In Ly ABC")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("payment-receivables:READ");
        assertThat(tool.alsoRequires()).containsExactly(PermissionCode.of("payment-receivables", Action.READ));
        verifyNoInteractions(customers, payments);
    }

    @Test
    @DisplayName("the accountant, who may not list customers, names one exactly; a fragment shows no names, no figures")
    void exactlyNamedWithoutTheCustomerList() {
        GetCustomerCreditTool.Answer fragment = tool.run(arguments("ABC"));

        assertThat(fragment.terms()).isNull();
        assertThat(fragment.receivables()).isNull();
        assertThat(fragment.candidates()).isEmpty();
        assertThat(fragment.note()).contains("full name exactly as registered").doesNotContain("Công ty In Ly ABC");
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("Sales, who may list customers, is offered the customers a fragment matches")
    void salesIsOfferedTheCandidates() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.SALES_STAFF).scope(DataScope.ALL)
                .can("customer-credit-profiles", Action.READ).can("payment-receivables", Action.READ)
                .can("customer-customers", Action.READ).build()));
        CustomerSummary other = new CustomerSummary(UUID.randomUUID(), null, "Công ty ABC Sài Gòn", "x@example.vn",
                null, null, null, "ACTIVE", 0, Instant.now());
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(ABC, other), 0, CustomerMatch.SEARCHED, 2));

        GetCustomerCreditTool.Answer answer = tool.run(arguments("ABC"));

        assertThat(answer.candidates()).extracting(candidate -> candidate.customerName().name())
                .containsExactly("Công ty In Ly ABC", "Công ty ABC Sài Gòn");
        assertThat(answer.terms()).isNull();
        verifyNoInteractions(payments);
    }
}
