package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
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
    private static final String MANY = "/api/v1/orders/credit-positions";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CustomerService customers;
    @Autowired SalesContracts contracts;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired com.stockflow.payment.api.PaymentService payments;

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

    /** Another customer, with no sign-in of its own. */
    private UUID anotherCustomer(String name) {
        UUID id = Identifiers.newId();
        UUID login = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, login, name, "credit-" + id + "@example.com"));
        return id;
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
        LocalDate today = signedWithOverdueOnSigning();

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
    @DisplayName("audit bo-29: the credit of a page of customers in one call - each item what the single position "
            + "says, in the order asked; a repeat once, an unknown id left out")
    void positionsOfAPage() throws Exception {
        credit();
        LocalDate today = signedWithOverdueOnSigning();
        UUID overLimit = anotherCustomer("Over the limit");
        customers.saveCreditTerms(new SaveCreditTermsCommand(overLimit, new BigDecimal("10000000"), 15, "test terms",
                accountant, null));
        confirmedOrders.signed(overLimit, List.of(share("AFTER_DELIVERY", "100", 15)),
                ConfirmedOrders.Line.cartons(CustomerSkuRows.issue(jdbc, tx, entityManager, overLimit, 50), 10,
                        40_000));
        UUID noCredit = anotherCustomer("No credit");
        UUID unknown = Identifiers.newId();

        http().asRole(Role.ACCOUNTANT, accountant, get(MANY)
                        .param("customerIds", noCredit + "," + customer + "," + unknown + "," + overLimit + ","
                                + noCredit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].customerId").value(noCredit.toString()))
                .andExpect(jsonPath("$.data[0].creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data[0].exposure").value(0))
                .andExpect(jsonPath("$.data[0].availableCredit").doesNotExist())
                .andExpect(jsonPath("$.data[0].hasOverdue").value(false))
                .andExpect(jsonPath("$.data[1].customerId").value(customer.toString()))
                .andExpect(jsonPath("$.data[1].creditLimit").value(50000000))
                .andExpect(jsonPath("$.data[1].creditTermDays").value(30))
                .andExpect(jsonPath("$.data[1].exposure").value(14000000))
                .andExpect(jsonPath("$.data[1].availableCredit").value(36000000))
                .andExpect(jsonPath("$.data[1].currency").value("VND"))
                .andExpect(jsonPath("$.data[1].hasOverdue").value(true))
                .andExpect(jsonPath("$.data[1].overdueAmount").value(5000000))
                .andExpect(jsonPath("$.data[1].oldestOverdueDueDate").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.data[2].customerId").value(overLimit.toString()))
                .andExpect(jsonPath("$.data[2].exposure").value(20000000))
                .andExpect(jsonPath("$.data[2].availableCredit").value(0))
                .andExpect(jsonPath("$.data[2].hasOverdue").value(false));
        // Sales reads the list's credit with the same grant as the single position; the parameter may be repeated.
        http().asRole(Role.SALES_STAFF, Identifiers.newId(), get(MANY).param("customerIds", customer.toString())
                        .param("customerIds", overLimit.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].exposure").value(14000000))
                .andExpect(jsonPath("$.data[1].exposure").value(20000000));
    }

    @Test
    @DisplayName("audit bo-29: the grouped statement reads what creditPosition reads, customer by customer - "
            + "receivable, overdue on signing, nothing owed, never heard of")
    void positionsReadAsTheSinglePosition() throws Exception {
        credit();
        signedWithOverdueOnSigning();
        UUID afterDelivery = anotherCustomer("After delivery only");
        customers.saveCreditTerms(new SaveCreditTermsCommand(afterDelivery, new BigDecimal("8000000"), 30,
                "test terms", accountant, null));
        confirmedOrders.signed(afterDelivery, List.of(share("AFTER_DELIVERY", "100", 30)),
                ConfirmedOrders.Line.cartons(CustomerSkuRows.issue(jdbc, tx, entityManager, afterDelivery, 50), 4,
                        25_000));
        UUID nothing = anotherCustomer("Owes nothing");
        UUID unknown = Identifiers.newId();
        List<UUID> ids = List.of(customer, afterDelivery, nothing, unknown);

        var many = payments.creditPositions(ids);

        assertThat(many).containsOnlyKeys(ids);
        for (UUID id : ids) {
            assertThat(many.get(id)).as("customer %s", id).usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(payments.creditPosition(id));
        }
        assertThat(many.get(customer).outstandingReceivables()).isEqualByComparingTo("14000000");
        assertThat(many.get(customer).overdueAmount()).isEqualByComparingTo("5000000");
        assertThat(many.get(afterDelivery).outstandingReceivables()).isEqualByComparingTo("5000000");
        assertThat(many.get(nothing).hasOverdue()).isFalse();
        assertThat(payments.creditPositions(List.of())).isEmpty();
    }

    @Test
    @DisplayName("audit bo-29: the page's credit is the back office's - a customer gets 403, a role without "
            + "customer-credit-profiles:READ 403, anonymous 401; past 200 ids 400 UNSUPPORTED_PARAMETER")
    void positionsRefused() throws Exception {
        http().asCustomer(user, get(MANY).param("customerIds", customer.toString()))
                .andExpect(status().isForbidden());
        http().asRole(Role.WAREHOUSE_STAFF, Identifiers.newId(), get(MANY).param("customerIds", customer.toString()))
                .andExpect(status().isForbidden());
        http().anonymous(get(MANY).param("customerIds", customer.toString())).andExpect(status().isUnauthorized());
        String tooMany = java.util.stream.Stream.generate(Identifiers::newId).limit(201).map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        http().asRole(Role.ACCOUNTANT, accountant, get(MANY).param("customerIds", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        http().asRole(Role.ACCOUNTANT, accountant, get(MANY).param("customerIds", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 10 cartons at 40,000 signed five days ago, 30% due on signing two days after sending (fell due three days ago,
     * 1,000,000 of its 6,000,000 paid) and 70% 30 days after delivery: 5,000,000 overdue, 14,000,000 exposure.
     *
     * @return today, in Vietnam
     */
    private LocalDate signedWithOverdueOnSigning() throws Exception {
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
        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND seq = 1",
                UUID.class, orderId);
        confirmedOrders.pay(customer, onSigning, new BigDecimal("1000000"));   // 6,000,000 due: 5,000,000 left
        return today;
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
