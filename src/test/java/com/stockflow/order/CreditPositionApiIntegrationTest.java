package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestPdfs;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * kltn-docs 15 §4.3, BR-10, BR-11 and 18 §2 over HTTP, through the real filter chain: a customer reads their own credit
 * ({@code GET /orders/credit-position/me}) - the limit, what their signed contracts owe after delivery, what is left,
 * and what is overdue since when - and accounting reads any customer's ({@code GET /orders/credit-position/{id}}).
 *
 * <p>Before 4B-10 this was {@code CheckoutPaymentTermsApiIntegrationTest}: the storefront read the payment terms it
 * could offer at checkout ({@code GET /orders/payment-terms}: allowed terms, default term, deposit share, balance
 * policies) and {@code POST /orders} refused a term or a policy the customer may not use. Those terms went with the
 * money ledger - a contract's installment plan says how a customer pays - and the endpoint is replaced by this one.
 * What it kept is the credit and the overdue figures, which since 4B-6 come from the installments, not from
 * receivables. Checkout's own {@code POST /orders} goes with 5-2 and is {@code PlaceOrderIntegrationTest}'s.</p>
 *
 * <p>Runs in the context the secured fulfillment tests share (the scan of a signed contract is not inspected).</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class CreditPositionApiIntegrationTest {

    private static final String MINE = "/api/v1/orders/credit-position/me";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CustomerService customers;
    @Autowired SalesContracts contracts;
    @Autowired InstallmentService installments;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID user;
    private UUID customer;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void aCustomerWithASignIn() {
        confirmedOrders = new ConfirmedOrders(context);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Credit customer', ?, 'ACTIVE', 0, NOW())""", customer, user,
                "credit-" + customer + "@example.com"));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    /** Credit approved by accounting: 50,000,000, 30 days to pay. */
    private void credit() {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("50000000"), 30, "test terms",
                accountant, null));
    }

    private static SalesContracts.Installment share(String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(milestone, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    @Test
    @DisplayName("no credit: no limit, nothing owed, nothing left to give, nothing overdue")
    void noCredit() throws Exception {
        http().asCustomer(user, get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data.creditTermDays").doesNotExist())
                .andExpect(jsonPath("$.data.exposure").value(0))
                .andExpect(jsonPath("$.data.availableCredit").doesNotExist())
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.hasOverdue").value(false))
                .andExpect(jsonPath("$.data.overdueAmount").value(0))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").doesNotExist());
    }

    @Test
    @DisplayName("15 BR-10: a signed contract's installments after delivery are the exposure; the rest of the limit is "
            + "what is left; accounting reads the same")
    void exposureFromSignedContracts() throws Exception {
        credit();
        // 10 cartons of 50 at 40,000: 20,000,000, all of it 30 days after delivery.
        confirmedOrders.signed(customer, List.of(share("AFTER_DELIVERY", "100", 30)),
                ConfirmedOrders.Line.cartons(sku, 10, 40_000));

        http().asCustomer(user, get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditLimit").value(50000000))
                .andExpect(jsonPath("$.data.creditTermDays").value(30))
                .andExpect(jsonPath("$.data.exposure").value(20000000))
                .andExpect(jsonPath("$.data.availableCredit").value(30000000))
                .andExpect(jsonPath("$.data.hasOverdue").value(false));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/orders/credit-position/" + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exposure").value(20000000))
                .andExpect(jsonPath("$.data.availableCredit").value(30000000));
    }

    @Test
    @DisplayName("15 §4.1 step 4, BR-11: what is still unpaid on installments past their due date, and the oldest date")
    void overdueAmountAndOldestDueDate() throws Exception {
        credit();
        UUID orderId = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, 40_000)));
        confirmedOrders.legalProfile(customer);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        var sales = confirmedOrders.sales();
        SalesContracts.ContractView sent = WithCurrentUser.call(sales, DataScope.ALL, () -> {
            SalesContracts.ContractView drafted = contracts.create(sales, orderId, new SalesContracts.Draft(null,
                    List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                            List.of(new SalesContracts.BatchLine(line, 10)))),
                    List.of(share("ON_SIGNING", "30", 2), share("AFTER_DELIVERY", "70", 30))));
            return contracts.send(sales, orderId, drafted.version());
        });
        // Sent six days ago, signed five days ago: the installment on signing fell due three days ago. A sent contract
        // is fixed (tg_sales_contract_transition), so its sending is moved back with triggers off, as
        // InstallmentReceiptIntegrationTest does - the one way to a past due day without a fake clock.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.sales_contract SET sent_at = sent_at - INTERVAL '6 days' WHERE id = ?",
                    sent.id());
        });
        byte[] scan = TestPdfs.blank();
        WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today.minusDays(5)));
        assertThat(installments.markOverdue()).isPositive();
        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND seq = 1",
                UUID.class, orderId);
        confirmedOrders.pay(customer, onSigning, new BigDecimal("1000000"));   // 6,000,000 due: 5,000,000 left

        http().asCustomer(user, get(MINE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOverdue").value(true))
                .andExpect(jsonPath("$.data.overdueAmount").value(5000000))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").value(today.minusDays(3).toString()))
                // The installment on signing is no receivable: only the 70% after delivery is exposure.
                .andExpect(jsonPath("$.data.exposure").value(14000000));
        http().asRole(Role.ACCOUNTANT, accountant, get("/api/v1/orders/credit-position/" + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueAmount").value(5000000))
                .andExpect(jsonPath("$.data.oldestOverdueDueDate").value(today.minusDays(3).toString()));
    }

    @Test
    @DisplayName("a sign-in without a customer profile (staff) gets 404 CUSTOMER_NOT_FOUND; anonymous gets 401; "
            + "a customer cannot read another customer's credit")
    void notForStaffOrAnonymous() throws Exception {
        http().asRole(Role.SALES_STAFF, UUID.randomUUID(), get(MINE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        http().anonymous(get(MINE)).andExpect(status().isUnauthorized());
        http().asCustomer(user, get("/api/v1/orders/credit-position/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
