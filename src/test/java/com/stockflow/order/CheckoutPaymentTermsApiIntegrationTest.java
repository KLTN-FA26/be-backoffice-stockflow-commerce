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
 * kltn-docs 15 BR-01 over HTTP, through the real filter chain: the portal reads the payment terms the signed-in
 * customer may use ({@code GET /orders/payment-terms}) and what it owes. It no longer places an order with one of
 * them: the customer does not place orders (kltn-docs 14 BR-01, BR-02; team plan 5-2), so {@code POST /orders} is
 * refused whatever it carries. What the order does with the terms is tested on the service.
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

    /** The order a test needs: placed through the service, the fixture until 4B-10; the customer has no way to. */
    private UUID anOrderOfTheCustomer() {
        UUID[] id = new UUID[1];
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            id[0] = orders.placeOrder(new com.stockflow.order.api.PlaceOrderCommand(UUID.randomUUID(), customer, null,
                    null, true, List.of(new com.stockflow.order.api.PlaceOrderCommand.Line(
                            new com.stockflow.common.domain.Sku("SOFA-3S-GREY"), 1,
                            com.stockflow.common.domain.Money.vnd(12_500_000), null)))).orderId();
            placed.add(id[0]);
        });
        return id[0];
    }

    @Test
    @DisplayName("team plan 5-2: a customer cannot place an order over HTTP, with any term; nothing is created")
    void aCustomerCannotPlaceAnOrder() throws Exception {
        terms(true, true, CommercialTerm.DEPOSIT);
        for (String term : List.of("PREPAID", "DEPOSIT", "CREDIT")) {
            http().asCustomer(user, post(ORDERS).contentType(MediaType.APPLICATION_JSON).content("""
                    {"requestId":"%s","customerId":"%s","paymentTerm":"%s",
                     "lines":[{"sku":"SOFA-3S-GREY","quantity":1,"unitPrice":12500000}]}"""
                    .formatted(UUID.randomUUID(), customer, term))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Long.class,
                customer)).isZero();
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
            + "oldestOverdueDueDate is the earliest due date; open, paid and carrier-collected ones (billed before "
            + "nothing was collected on delivery any more) do not count")
    void overdueAmountAndOldestDueDate() throws Exception {
        terms(false, true, CommercialTerm.CREDIT);
        receivable("OVERDUE", "CUSTOMER", 1_000_000, 200_000, -10);     // 800k unpaid, the oldest
        receivable("OVERDUE", "CUSTOMER", 500_000, 0, -3);              // 500k unpaid
        receivable("OPEN", "CUSTOMER", 9_000_000, 0, 15);               // not due yet
        receivable("OVERDUE", "CARRIER", 7_000_000, 0, -30);            // the carrier's to remit, not the customer's
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

    /** SCRUM-484: terms that exist are changed against the version read; the first set carries none. */
    private Long currentTermsVersion() {
        var terms = customers.creditTerms(customer);
        return terms.configured() ? terms.version() : null;
    }

    private void depositTerms(boolean credit, String limit, com.stockflow.customer.api.DepositBalancePolicy policy) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, credit, CommercialTerm.DEPOSIT,
                new BigDecimal("30"), credit ? new BigDecimal(limit) : null, credit ? 30 : null, "test terms", approver,
                currentTermsVersion(), policy));
    }

    @Test
    @DisplayName("SCRUM-193: payment-terms lists the balance policies the customer may pick: none without deposits; "
            + "paid first and the credit one with credit; paid first only without credit")
    void allowedBalancePolicies() throws Exception {
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(0));
        depositTerms(true, "10000000", com.stockflow.customer.api.DepositBalancePolicy.ON_CREDIT);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.depositBalancePolicy").value("ON_CREDIT"))
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(2))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[0]").value("BEFORE_DELIVERY"))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[1]").value("ON_CREDIT"));
        depositTerms(false, null, com.stockflow.customer.api.DepositBalancePolicy.BEFORE_DELIVERY);
        http().asCustomer(user, get(TERMS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedBalancePolicies.length()").value(1))
                .andExpect(jsonPath("$.data.allowedBalancePolicies[0]").value("BEFORE_DELIVERY"));
    }

}
