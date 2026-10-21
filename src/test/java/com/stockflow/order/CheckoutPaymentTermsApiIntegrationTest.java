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
}
