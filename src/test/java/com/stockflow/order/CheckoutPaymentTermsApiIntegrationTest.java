package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * kltn-docs 15 BR-01 over HTTP, through the real filter chain: the storefront checkout reads the payment terms
 * the signed-in customer may use ({@code GET /orders/payment-terms}) and places the order with one of them
 * ({@code POST /orders}); a term the customer may not use is refused with {@code PAYMENT_TERM_NOT_ALLOWED}, and
 * a customer cannot pick the deposit share.
 */
@SecuredIntegrationTest
class CheckoutPaymentTermsApiIntegrationTest {

    private static final String TERMS = "/api/v1/orders/payment-terms";
    private static final String ORDERS = "/api/v1/orders";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID user;
    private UUID customer;
    private UUID approver;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void aCustomerWithASignIn() {
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Checkout customer', ?, 'ACTIVE', 0, NOW())""", customer, user,
                "checkout-" + customer + "@example.com"));
        customers.addAddress(customer, null, true, new com.stockflow.customer.api.SaveAddressCommand("SHIPPING",
                "Nguyen Van A", "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null,
                true, null));
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            if (orders.findById(id).orElseThrow().status() != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private void terms(boolean deposit, boolean credit, CommercialTerm defaultTerm) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, deposit, credit, defaultTerm,
                deposit ? new BigDecimal("30") : null, credit ? new BigDecimal("50000000") : null, credit ? 45 : null,
                "test terms", approver, null));
    }

    private ResultActions place(String extra) throws Exception {
        return http().asCustomer(user, post(ORDERS).contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","customerId":"%s","lines":[{"sku":"SOFA-3S-GREY","quantity":1,"unitPrice":12500000}]%s}"""
                .formatted(UUID.randomUUID(), customer, extra)));
    }

    private JsonNode placedBody(ResultActions result) throws Exception {
        JsonNode body = json.readTree(result.andReturn().getResponse().getContentAsString());
        if (body.at("/data/orderId").isTextual()) placed.add(UUID.fromString(body.at("/data/orderId").asText()));
        return body;
    }

    @Test
    @DisplayName("a customer with no terms set is offered PREPAID only")
    void prepaidOnlyByDefault() throws Exception {
        JsonNode data = json.readTree(http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(data.get("allowedTerms")).extracting(JsonNode::asText).containsExactly("PREPAID");
        assertThat(data.get("defaultTerm").asText()).isEqualTo("PREPAID");
        assertThat(data.has("depositPercent")).isFalse();
    }

    @Test
    @DisplayName("the terms read is the caller's own customer's: allowed terms, default, deposit share")
    void termsOfTheCallersOwnCustomer() throws Exception {
        terms(true, true, CommercialTerm.DEPOSIT);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data.allowedTerms.length()").value(3))
                .andExpect(jsonPath("$.data.allowedTerms[0]").value("PREPAID"))
                .andExpect(jsonPath("$.data.allowedTerms[1]").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.allowedTerms[2]").value("CREDIT"))
                .andExpect(jsonPath("$.data.defaultTerm").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.depositPercent").value(30));
    }

    @Test
    @DisplayName("a sign-in without a customer profile (staff) gets 404 CUSTOMER_NOT_FOUND; anonymous gets 401")
    void notForStaffOrAnonymous() throws Exception {
        http().asRole(com.stockflow.common.security.Role.SALES_STAFF, UUID.randomUUID(), get(TERMS))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        http().anonymous(get(TERMS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BR-01: POST /orders with a term the customer may not use is 409 PAYMENT_TERM_NOT_ALLOWED")
    void aTermNotAllowedIsRefused() throws Exception {
        place(",\"paymentTerm\":\"CREDIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_TERM_NOT_ALLOWED"));
        place(",\"paymentTerm\":\"DEPOSIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_TERM_NOT_ALLOWED"));
        place(",\"paymentTerm\":\"BARTER\"").andExpect(status().isBadRequest());
        // nothing was reserved or created by the refusals
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Long.class, customer))
                .isZero();
    }

    @Test
    @DisplayName("an allowed term, or none (the default), places the order on that term")
    void anAllowedTermIsAccepted() throws Exception {
        terms(true, false, CommercialTerm.DEPOSIT);
        JsonNode byDefault = placedBody(place("").andExpect(status().isCreated()));
        assertThat(byDefault.at("/data/paymentTerm").asText()).isEqualTo("DEPOSIT");
        assertThat(byDefault.at("/data/depositRequired").decimalValue()).isEqualByComparingTo("3750000");
        JsonNode prepaid = placedBody(place(",\"paymentTerm\":\"PREPAID\"").andExpect(status().isCreated()));
        assertThat(prepaid.at("/data/paymentTerm").asText()).isEqualTo("PREPAID");
        place(",\"paymentTerm\":\"CREDIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_TERM_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("a customer cannot pick the deposit share: theirs applies, whatever they send")
    void theDepositShareIsNotTheCustomersToPick() throws Exception {
        terms(true, false, CommercialTerm.PREPAID);
        JsonNode body = placedBody(place(",\"paymentTerm\":\"DEPOSIT\",\"depositPercent\":1")
                .andExpect(status().isCreated()));
        assertThat(body.at("/data/depositRequired").decimalValue()).isEqualByComparingTo("3750000");
    }

    /** A receivable of {@code amount}, {@code paid} of it settled, on a fresh order of the customer. */
    private void receivable(String status, String collector, long amount, long paid, int dueInDays) throws Exception {
        UUID order = UUID.fromString(placedBody(place(",\"paymentTerm\":\"PREPAID\"").andExpect(status().isCreated()))
                .at("/data/orderId").asText());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.receivable (id, order_id, customer_id, amount, paid_amount, currency, issued_at,
                    due_date, status, collector, kind)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'VND', NOW() - INTERVAL '40 days', CURRENT_DATE + ?, ?, ?, ?)""",
                order, customer, amount, paid, dueInDays, status, collector,
                "CARRIER".equals(collector) ? "DEPOSIT_BALANCE" : "CREDIT_ORDER"));
    }

    @Test
    @DisplayName("SCRUM-193: a customer with nothing overdue sees overdueAmount 0 and no oldestOverdueDueDate")
    void nothingOverdue() throws Exception {
        terms(false, true, CommercialTerm.CREDIT);
        receivable("OPEN", "CUSTOMER", 2_000_000, 0, 20);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOverdue").value(false))
                .andExpect(jsonPath("$.data.overdueAmount").value(0))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").doesNotExist());
    }

    @Test
    @DisplayName("SCRUM-193: overdueAmount sums what is still unpaid on the customer's OVERDUE receivables and "
            + "oldestOverdueDueDate is the earliest due date; open, paid and carrier-collected ones do not count")
    void overdueAmountAndOldestDueDate() throws Exception {
        terms(false, true, CommercialTerm.CREDIT);
        receivable("OVERDUE", "CUSTOMER", 1_000_000, 200_000, -10);     // 800k unpaid, the oldest
        receivable("OVERDUE", "CUSTOMER", 500_000, 0, -3);              // 500k unpaid
        receivable("OPEN", "CUSTOMER", 9_000_000, 0, 15);               // not due yet
        receivable("OVERDUE", "CARRIER", 7_000_000, 0, -30);            // the carrier's to collect, not the customer's
        String oldest = java.time.LocalDate.now().minusDays(10).toString();

        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOverdue").value(true))
                .andExpect(jsonPath("$.data.overdueAmount").value(1300000))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").value(oldest))
                // the existing fields are unchanged
                .andExpect(jsonPath("$.data.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.creditLimit").value(50000000));
        // the staff read of the same customer carries the same two fields
        http().asRole(com.stockflow.common.security.Role.ACCOUNTANT, approver, get("/api/v1/orders/credit-position/" + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueAmount").value(1300000))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").value(oldest));
    }

    // ------------------------------------------------------------------ SCRUM-193: the balance policy on the portal

    private void depositTerms(boolean credit, String limit, com.stockflow.customer.api.DepositBalancePolicy policy) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, credit, CommercialTerm.DEPOSIT,
                new BigDecimal("30"), credit ? new BigDecimal(limit) : null, credit ? 30 : null, "test terms", approver,
                null, policy));
    }

    private ResultActions placeTwo(String extra) throws Exception {
        return http().asCustomer(user, post(ORDERS).contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","customerId":"%s","lines":[{"sku":"SOFA-3S-GREY","quantity":2,"unitPrice":12500000}],
                 "paymentTerm":"DEPOSIT"%s}""".formatted(UUID.randomUUID(), customer, extra)));
    }

    @Test
    @DisplayName("SCRUM-193: payment-terms lists the balance policies the customer may pick: none without deposits; "
            + "paid first and the credit one with credit; their own ON_DELIVERY when their terms say so")
    void allowedBalancePolicies() throws Exception {
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(0));
        depositTerms(true, "10000000", com.stockflow.customer.api.DepositBalancePolicy.ON_CREDIT);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.depositBalancePolicy").value("ON_CREDIT"))
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(2))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[0]").value("BEFORE_DELIVERY"))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[1]").value("ON_CREDIT"));
        depositTerms(false, null, com.stockflow.customer.api.DepositBalancePolicy.ON_DELIVERY);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(2))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[1]").value("ON_DELIVERY"));
    }

    @Test
    @DisplayName("SCRUM-193 CREDIT-PORTAL: a portal order whose balance does not fit on credit is 409 "
            + "BALANCE_CREDIT_LIMIT_EXCEEDED with the credit left and the currency in details - no order, no silent "
            + "switch to BEFORE_DELIVERY - whether the policy is the customer's default or their own pick")
    void aBalanceOverTheLimitIsRefusedWithTheCreditLeft() throws Exception {
        depositTerms(true, "10000000", com.stockflow.customer.api.DepositBalancePolicy.ON_CREDIT);
        // 25M order, 7.5M deposit, 17.5M balance on credit > the 10M limit
        placeTwo("").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.availableCredit").value(10000000))
                .andExpect(jsonPath("$.details.creditLimit").value(10000000))
                .andExpect(jsonPath("$.details.requiredAmount").value(17500000))
                .andExpect(jsonPath("$.details.currency").value("VND"));
        placeTwo(",\"balancePolicy\":\"ON_CREDIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_CREDIT_LIMIT_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Long.class,
                customer)).isZero();
    }

    @Test
    @DisplayName("SCRUM-193: the customer picks BEFORE_DELIVERY and is placed even over the limit; within the limit the "
            + "ON_CREDIT default is placed and the credit left shrinks; a limit used up leaves availableCredit 0, not below")
    void aCustomerMayPickAnAllowedPolicy() throws Exception {
        depositTerms(true, "10000000", com.stockflow.customer.api.DepositBalancePolicy.ON_CREDIT);
        JsonNode paidFirst = placedBody(placeTwo(",\"balancePolicy\":\"BEFORE_DELIVERY\"").andExpect(status().isCreated()));
        assertThat(paidFirst.at("/data/balancePolicy").asText()).isEqualTo("BEFORE_DELIVERY");
        // one sofa: 12.5M, deposit 3.75M, balance 8.75M fits in 10M
        JsonNode onCredit = placedBody(place(",\"paymentTerm\":\"DEPOSIT\"").andExpect(status().isCreated()));
        assertThat(onCredit.at("/data/balancePolicy").asText()).isEqualTo("ON_CREDIT");
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exposure").value(8750000))
                .andExpect(jsonPath("$.data.availableCredit").value(1250000));
        // the next one no longer fits: refused, with what is left
        place(",\"paymentTerm\":\"DEPOSIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.availableCredit").value(1250000));
    }

    @Test
    @DisplayName("SCRUM-193: a policy the customer's terms do not allow is 409 BALANCE_POLICY_NOT_ALLOWED; a balance "
            + "policy on a term with no balance is a 400")
    void aPolicyNotAllowedIsRefused() throws Exception {
        depositTerms(false, null, com.stockflow.customer.api.DepositBalancePolicy.BEFORE_DELIVERY);
        place(",\"paymentTerm\":\"DEPOSIT\",\"balancePolicy\":\"ON_CREDIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_POLICY_NOT_ALLOWED"));
        place(",\"paymentTerm\":\"DEPOSIT\",\"balancePolicy\":\"ON_DELIVERY\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BALANCE_POLICY_NOT_ALLOWED"));
        place(",\"paymentTerm\":\"PREPAID\",\"balancePolicy\":\"BEFORE_DELIVERY\"")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        JsonNode own = placedBody(place(",\"paymentTerm\":\"DEPOSIT\",\"balancePolicy\":\"BEFORE_DELIVERY\"")
                .andExpect(status().isCreated()));
        assertThat(own.at("/data/balancePolicy").asText()).isEqualTo("BEFORE_DELIVERY");
    }

    @Test
    @DisplayName("SCRUM-193: an overdue customer's ON_CREDIT balance is 409 CUSTOMER_CREDIT_OVERDUE with the overdue "
            + "amount, its oldest due date and the credit left in details; BEFORE_DELIVERY is still theirs to pick")
    void anOverdueCustomerIsToldHowMuch() throws Exception {
        depositTerms(true, "10000000", com.stockflow.customer.api.DepositBalancePolicy.ON_CREDIT);
        receivable("OVERDUE", "CUSTOMER", 1_000_000, 0, -12);
        place(",\"paymentTerm\":\"DEPOSIT\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_CREDIT_OVERDUE"))
                .andExpect(jsonPath("$.details.overdueAmount").value(1000000))
                .andExpect(jsonPath("$.details.oldestOverdueDueDate").value(java.time.LocalDate.now().minusDays(12).toString()))
                .andExpect(jsonPath("$.details.availableCredit").value(9000000))
                .andExpect(jsonPath("$.details.currency").value("VND"));
        JsonNode paidFirst = placedBody(place(",\"paymentTerm\":\"DEPOSIT\",\"balancePolicy\":\"BEFORE_DELIVERY\"")
                .andExpect(status().isCreated()));
        assertThat(paidFirst.at("/data/balancePolicy").asText()).isEqualTo("BEFORE_DELIVERY");
    }
}
