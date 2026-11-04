package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.payment.internal.service.ReceivableService;
import com.stockflow.support.Await;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-193 over HTTP, through the real filter chain: {@code GET /orders/me/{id}/transfer-instructions} tells a customer
 * which account to pay, what to write as the content (the order number, 15 §3) and what is still due now - the
 * prepayment or deposit while unpaid, the balance before delivery, nothing on credit - from what payment's ledger holds.
 * A customer reaches their own orders only (OWN scope).
 */
@SecuredIntegrationTest
class TransferInstructionsIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID user;
    private UUID customer;
    private UUID accountant;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void aCustomerWithASignIn() {
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Transfer customer', ?, 'ACTIVE', 0, NOW())""", customer, user,
                "transfer-" + customer + "@example.com"));
        customers.addAddress(customer, null, true, new com.stockflow.customer.api.SaveAddressCommand("SHIPPING",
                "Nguyen Van A", "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null,
                true, null));
        terms(DepositBalancePolicy.BEFORE_DELIVERY);
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

    private void terms(DepositBalancePolicy policy) {
        Long version = jdbc.queryForObject("SELECT COALESCE(MAX(version), -1) FROM customer.credit_profile "
                + "WHERE customer_id = ?", Long.class, customer);
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.PREPAID,
                new BigDecimal("30"), new BigDecimal("50000000"), 30, "test terms", accountant,
                version == null || version < 0 ? null : version, policy));
    }

    /** One sofa (12 500 000) on {@code term}; the order's id and number. */
    private JsonNode place(String term) throws Exception {
        ResultActions result = http().asCustomer(user, post(ORDERS).contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","customerId":"%s","lines":[{"sku":"SOFA-3S-GREY","quantity":1,"unitPrice":12500000}],
                 "paymentTerm":"%s"}""".formatted(UUID.randomUUID(), customer, term)));
        JsonNode body = json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("data");
        placed.add(UUID.fromString(body.get("orderId").asText()));
        return body;
    }

    private ResultActions instructions(JsonNode order) throws Exception {
        return http().asCustomer(user, get(ORDERS + "/me/" + order.get("orderId").asText() + "/transfer-instructions"));
    }

    private void transfer(JsonNode order, long amount) {
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, "FT" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12), BigDecimal.valueOf(amount), "VND", TODAY, null, "payment",
                accountant, List.of(new ReceivableService.OrderPayment(UUID.fromString(order.get("orderId").asText()),
                BigDecimal.valueOf(amount)))));
    }

    private OrderStatus statusOf(JsonNode order) {
        return orders.findById(UUID.fromString(order.get("orderId").asText())).orElseThrow().status();
    }

    @Test
    @DisplayName("a prepaid order: the account, the order number as the content, the whole total due to confirm it")
    void prepaid() throws Exception {
        JsonNode order = place("PREPAID");
        instructions(order).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.get("orderId").asText()))
                .andExpect(jsonPath("$.data.orderNumber").value(order.get("orderNumber").asText()))
                .andExpect(jsonPath("$.data.transferReference").value(order.get("orderNumber").asText()))
                .andExpect(jsonPath("$.data.bankName").value("DEMO BANK (not a real bank)"))
                .andExpect(jsonPath("$.data.accountNumber").value("0000000000"))
                .andExpect(jsonPath("$.data.accountName").value("DEMO STOCKFLOW CO LTD"))
                .andExpect(jsonPath("$.data.amountDue").value(12500000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.dueBy").value("TO_CONFIRM"))
                .andExpect(jsonPath("$.data.dueByNote").isNotEmpty());
    }

    @Test
    @DisplayName("a partial transfer lowers what is due; once paid in full nothing is due (NONE)")
    void prepaidPartlyThenFullyPaid() throws Exception {
        JsonNode order = place("PREPAID");
        transfer(order, 5_000_000);
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(7500000))
                .andExpect(jsonPath("$.data.dueBy").value("TO_CONFIRM"));
        transfer(order, 7_500_000);
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(0))
                .andExpect(jsonPath("$.data.dueBy").value("NONE"));
    }

    @Test
    @DisplayName("a deposit order paid before delivery: the deposit first, then the balance before the goods leave")
    void depositThenBalance() throws Exception {
        JsonNode order = place("DEPOSIT");                                    // 12.5M, deposit 3.75M
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(3750000))
                .andExpect(jsonPath("$.data.dueBy").value("TO_CONFIRM"));
        transfer(order, 3_750_000);
        Await.until("the deposit confirms the order", Duration.ofSeconds(15), () -> statusOf(order) == OrderStatus.CONFIRMED);
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(8750000))
                .andExpect(jsonPath("$.data.dueBy").value("BEFORE_DELIVERY"));
        transfer(order, 8_750_000);
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(0))
                .andExpect(jsonPath("$.data.dueBy").value("NONE"));
    }

    @Test
    @DisplayName("a deposit order whose balance the carrier collects owes only the deposit; nothing after it")
    void depositWithBalanceOnDelivery() throws Exception {
        terms(DepositBalancePolicy.ON_DELIVERY);
        JsonNode order = place("DEPOSIT");
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(3750000));
        transfer(order, 3_750_000);
        Await.until("the deposit confirms the order", Duration.ofSeconds(15), () -> statusOf(order) == OrderStatus.CONFIRMED);
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(0))
                .andExpect(jsonPath("$.data.dueBy").value("NONE"));
    }

    @Test
    @DisplayName("a credit order owes nothing to transfer now")
    void credit() throws Exception {
        JsonNode order = place("CREDIT");
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(0))
                .andExpect(jsonPath("$.data.dueBy").value("NONE"))
                .andExpect(jsonPath("$.data.transferReference").value(order.get("orderNumber").asText()));
    }

    @Test
    @DisplayName("a cancelled order owes nothing")
    void cancelled() throws Exception {
        JsonNode order = place("PREPAID");
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.cancel(UUID.fromString(order.get("orderId").asText()), "TEST"));
        instructions(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(0))
                .andExpect(jsonPath("$.data.dueBy").value("NONE"));
    }

    @Test
    @DisplayName("OWN scope: another customer's order, or none, is 404; staff without a profile 404 CUSTOMER_NOT_FOUND; "
            + "anonymous 401; a role without sales-orders:READ 403")
    void whoMayRead() throws Exception {
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        var theirs = orders.placeOrder(new com.stockflow.order.api.PlaceOrderCommand(UUID.randomUUID(), other, null, null,
                false, List.of(new com.stockflow.order.api.PlaceOrderCommand.Line(
                        new com.stockflow.common.domain.Sku("SOFA-3S-GREY"), 1,
                        com.stockflow.common.domain.Money.vnd(12_500_000), null))));
        placed.add(theirs.orderId());

        http().asCustomer(user, get(ORDERS + "/me/" + theirs.orderId() + "/transfer-instructions"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        http().asCustomer(user, get(ORDERS + "/me/" + UUID.randomUUID() + "/transfer-instructions"))
                .andExpect(status().isNotFound());
        http().asRole(Role.SALES_STAFF, UUID.randomUUID(), get(ORDERS + "/me/" + theirs.orderId() + "/transfer-instructions"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        http().anonymous(get(ORDERS + "/me/" + theirs.orderId() + "/transfer-instructions"))
                .andExpect(status().isUnauthorized());
        http().asRole(Role.QC_STAFF, UUID.randomUUID(), get(ORDERS + "/me/" + theirs.orderId() + "/transfer-instructions"))
                .andExpect(status().isForbidden());
    }
}
