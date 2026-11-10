package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-193 / 4B-6 over HTTP, through the real filter chain: {@code GET /installments/me/{id}/transfer-instructions}
 * tells a customer which account to pay, what to write as the content (the contract number and the installment, 15 §2)
 * and what is still due on it; a customer reaches their own installments only (OWN scope).
 *
 * <p>Before 4B-10 the instructions were the order's ({@code GET /orders/me/{id}/transfer-instructions}): the
 * prepayment or deposit while unpaid, the balance before delivery, nothing on credit, read from the money ledger. An
 * order is now paid by its contract's installments, so each installment carries its own instructions, and what the old
 * "what is due now" said is now which installment is due: the one on signing to confirm the order, the one before
 * delivery before the goods leave, nothing for the installment after delivery until the customer chooses to pay it
 * early. A paid or cancelled installment takes no more money (409 {@code INSTALLMENT_NOT_PAYABLE}) - the old "NONE".</p>
 *
 * <p>Runs in the context the secured fulfillment tests share (the scan of a signed contract is not inspected).</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class TransferInstructionsIntegrationTest {

    private static final String INSTALLMENTS = "/api/v1/installments";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID user;
    private UUID customer;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void aCustomerWithASignIn() {
        confirmedOrders = new ConfirmedOrders(context);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Transfer customer', ?, 'ACTIVE', 0, NOW())""", customer, user,
                "transfer-" + customer + "@example.com"));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    /** 5 cartons of 50 at 50,000 a piece, no VAT: 12,500,000, with {@code plan}, signed today. */
    private ConfirmedOrders.Signed signed(SalesContracts.Installment... plan) {
        return confirmedOrders.signed(customer, List.of(plan), ConfirmedOrders.Line.cartons(sku, 5, 50_000));
    }

    private ResultActions instructions(UUID installmentId) throws Exception {
        return http().asCustomer(user, get(INSTALLMENTS + "/me/" + installmentId + "/transfer-instructions"));
    }

    private void transfer(ConfirmedOrders.Signed signed, int seq, long amount) {
        confirmedOrders.pay(customer, signed.seq(seq), BigDecimal.valueOf(amount));
    }

    @Test
    @DisplayName("paid on signing: the account, the contract and installment as the content, the whole total due")
    void paidOnSigning() throws Exception {
        ConfirmedOrders.Signed signed = signed(share("On signing", "ON_SIGNING", "100", 0));
        instructions(signed.seq(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installmentId").value(signed.seq(1).toString()))
                .andExpect(jsonPath("$.data.contractNumber").value(signed.contractNumber()))
                .andExpect(jsonPath("$.data.orderNumber").value(signed.orderNumber()))
                .andExpect(jsonPath("$.data.seq").value(1))
                .andExpect(jsonPath("$.data.reference").value(signed.contractNumber() + " DOT1"))
                .andExpect(jsonPath("$.data.bankName").value("DEMO BANK (not a real bank)"))
                .andExpect(jsonPath("$.data.accountNumber").value("0000000000"))
                .andExpect(jsonPath("$.data.accountName").value("DEMO STOCKFLOW CO LTD"))
                .andExpect(jsonPath("$.data.amountDue").value(12500000))
                .andExpect(jsonPath("$.data.currency").value("VND"));
    }

    @Test
    @DisplayName("a partial transfer lowers what is due; once paid in full nothing more is taken (409)")
    void partlyThenFullyPaid() throws Exception {
        ConfirmedOrders.Signed signed = signed(share("On signing", "ON_SIGNING", "100", 0));
        transfer(signed, 1, 5_000_000);
        instructions(signed.seq(1)).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(7500000));
        transfer(signed, 1, 7_500_000);
        instructions(signed.seq(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_PAYABLE"));
    }

    @Test
    @DisplayName("a deposit on signing, the rest before delivery: each installment its own content and amount")
    void depositThenBeforeDelivery() throws Exception {
        ConfirmedOrders.Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0),
                share("Before delivery", "BEFORE_DELIVERY", "70", null));
        instructions(signed.seq(1)).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(3750000));
        instructions(signed.seq(2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.amountDue").value(8750000))
                .andExpect(jsonPath("$.data.reference").value(signed.contractNumber() + " DOT2"))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
        transfer(signed, 1, 3_750_000);
        instructions(signed.seq(1)).andExpect(status().isConflict());
        transfer(signed, 2, 8_750_000);
        instructions(signed.seq(2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_PAYABLE"));
    }

    @Test
    @DisplayName("paid after delivery: not due yet - no due day - but the customer may pay it early")
    void afterDelivery() throws Exception {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("50000000"), 30, "test terms",
                confirmedOrders.accountant(), null));
        ConfirmedOrders.Signed signed = signed(share("After delivery", "AFTER_DELIVERY", "100", 30));
        instructions(signed.seq(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amountDue").value(12500000))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    @Test
    @DisplayName("a cancelled order's unpaid installments take nothing")
    void cancelled() throws Exception {
        ConfirmedOrders.Signed signed = signed(share("On signing", "ON_SIGNING", "100", 0));
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(signed.orderId(), "TEST"));
        instructions(signed.seq(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_PAYABLE"));
    }

    @Test
    @DisplayName("OWN scope: another customer's installment, or none, is 404; staff without a profile 404 "
            + "CUSTOMER_NOT_FOUND; anonymous 401; a role without payment-installments:READ 403; Sales reads any")
    void whoMayRead() throws Exception {
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued theirSku = CustomerSkuRows.issue(jdbc, tx, entityManager, other, 50);
        ConfirmedOrders.Signed theirs = confirmedOrders.signed(other, ConfirmedOrders.Line.cartons(theirSku, 5,
                50_000));
        String url = INSTALLMENTS + "/me/" + theirs.seq(1) + "/transfer-instructions";

        http().asCustomer(user, get(url)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_FOUND"));
        http().asCustomer(user, get(INSTALLMENTS + "/me/" + UUID.randomUUID() + "/transfer-instructions"))
                .andExpect(status().isNotFound());
        http().asRole(Role.SALES_STAFF, UUID.randomUUID(), get(url))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        http().anonymous(get(url)).andExpect(status().isUnauthorized());
        http().asRole(Role.INVENTORY_PLANNER, UUID.randomUUID(), get(url)).andExpect(status().isForbidden());
        // 15 §2, 16: Sales sends any customer the details from the back office.
        http().asRole(Role.SALES_STAFF, UUID.randomUUID(), get(INSTALLMENTS + "/" + theirs.seq(1)
                + "/transfer-instructions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amountDue").value(12500000));
    }
}
