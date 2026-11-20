package com.stockflow.payment;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.CustomerInstallments;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FE BE-5, BE-6 and BE-16 over HTTP, on contracts signed through the real flow ({@link ConfirmedOrders}):
 * <ul>
 *   <li>{@code /installments?owing=true} is the receivables screen - due and still owed, the fee items with it - and
 *       never an installment whose milestone has not come;</li>
 *   <li>{@code /installments?search=} finds a customer's installments by a part of its name (case and accents aside)
 *       or of its tax code, for the accountant who may not open the customer list; a search matching nobody finds
 *       nothing, never everything;</li>
 *   <li>a customer's open installments say what was taken off them and what they still owe, so the chat does not
 *       ask the customer for money a closure already took off.</li>
 * </ul>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class InstallmentReceivablesApiIntegrationTest {

    /** Half on signing, due at once; half after delivery, 30 days to pay. */
    private static final List<SalesContracts.Installment> HALF_AND_HALF = List.of(
            new SalesContracts.Installment("Dat coc", "ON_SIGNING", null, new BigDecimal("50"), 0, null),
            new SalesContracts.Installment("Sau giao", "AFTER_DELIVERY", null, new BigDecimal("50"), 30, null));

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired PaymentService payments;
    @Autowired CustomerInstallments customerInstallments;
    @Autowired CustomerService customers;

    private ConfirmedOrders orders;
    private UUID accountant;
    /** Unique to this run, so the search finds this test's customer and no other. */
    private String token;
    private String taxCode;
    private UUID customer;
    private ConfirmedOrders.Signed signed;

    @BeforeEach
    void aSignedContract() {
        orders = new ConfirmedOrders(context);
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        token = "Đức" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 41), 36).toUpperCase();
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", customer, "Công ty Bao Bì " + token,
                "receivables-" + customer + "@example.com", taxCode));
        // Paying after delivery is credit: approved by accounting, ample, so signing holds nothing for it.
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000000"), 30,
                "ample credit", accountant, null));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        signed = orders.signed(customer, HALF_AND_HALF, ConfirmedOrders.Line.cartons(sku, 10, 40_000));
    }

    private List<Map<String, Object>> rows(MockHttpServletRequestBuilder request) throws Exception {
        String body = new SecuredMvc(mvc, decoder).asRole(Role.ACCOUNTANT, accountant, request.param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.items");
    }

    private static List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("installmentId")).toList();
    }

    @Test
    @DisplayName("BE-16: by a part of the name, any case and without accents, or a part of the tax code; nobody: nothing")
    void searchByCustomer() throws Exception {
        List<Object> both = List.of(signed.seq(1).toString(), signed.seq(2).toString());
        // "Đức..." typed as "duc...": accents and case aside.
        String typed = token.replace("Đ", "d").toLowerCase();
        assertThat(ids(rows(get("/api/v1/installments").param("search", typed))))
                .containsExactlyInAnyOrderElementsOf(both);
        assertThat(ids(rows(get("/api/v1/installments").param("search", taxCode.substring(2, 9)))))
                .containsAll(both);
        assertThat(rows(get("/api/v1/installments").param("search", typed)).getFirst())
                .as("each row names its customer").containsEntry("customerName", "Công ty Bao Bì " + token)
                .containsEntry("customerCode", taxCode);
        assertThat(rows(get("/api/v1/installments").param("search", "khong-co-khach-nao-" + token)))
                .as("a search matching no customer finds no installment, never every one").isEmpty();
    }

    @Test
    @DisplayName("BE-5: owing=true is what is due and still owed - paid in part, the fee item - never what is not due")
    void owing() throws Exception {
        // A closure of the rest keeping a fee nothing collected covers: the fee is owed on a fee item, due at once. A
        // fee is kept only once mass printing started (17 BR-11), as CancellationRefundIntegrationTest marks it.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = ?", signed.orderId()));
        tx.execute(s -> payments.settleRemainderClosure(new SettleRemainderClosureCommand(signed.orderId(),
                Identifiers.newId(), new BigDecimal("300000"), new BigDecimal("200000"), "Printed, never shipped",
                "100 cups printed", accountant, Instant.now())));
        UUID fee = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND milestone = "
                + "'CANCELLATION_FEE'", UUID.class, signed.orderId());
        orders.pay(customer, signed.seq(1), new BigDecimal("1000"));
        String typed = token.toLowerCase();

        List<Map<String, Object>> owing = rows(get("/api/v1/installments").param("search", typed)
                .param("owing", "true"));
        assertThat(ids(owing)).as("the deposit paid in part and the cancellation fee item; the installment after delivery has no "
                + "milestone yet").containsExactlyInAnyOrder(signed.seq(1).toString(), fee.toString());
        assertThat(owing).extracting(r -> r.get("status")).containsExactlyInAnyOrder("PARTIALLY_PAID", "DUE");
        assertThat(rows(get("/api/v1/installments").param("search", typed).param("owing", "false")))
                .as("false filters nothing").hasSize(3);

        // Paid in full, it owes nothing any more.
        BigDecimal rest = jdbc.queryForObject("SELECT amount - paid_amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, signed.seq(1));
        orders.pay(customer, signed.seq(1), rest);
        assertThat(ids(rows(get("/api/v1/installments").param("search", typed).param("owing", "true"))))
                .containsExactly(fee.toString());
    }

    @Test
    @DisplayName("BE-6: an open installment a closure lowered says what was taken off and what it still owes")
    void openInstallmentOwesWhatIsLeft() {
        BigDecimal afterDelivery = jdbc.queryForObject("SELECT amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, signed.seq(2));
        BigDecimal closed = new BigDecimal("100000");
        tx.execute(s -> payments.settleRemainderClosure(new SettleRemainderClosureCommand(signed.orderId(),
                Identifiers.newId(), closed, null, null, null, accountant, Instant.now())));

        CustomerInstallments.OpenInstallment open = customerInstallments.openOf(customer, 10).stream()
                .filter(i -> i.id().equals(signed.seq(2))).findFirst().orElseThrow();
        assertThat(open.amount()).isEqualByComparingTo(afterDelivery);
        assertThat(open.reducedAmount()).isEqualByComparingTo(closed);
        assertThat(open.outstanding()).as("amount - paid - reduced, not amount - paid")
                .isEqualByComparingTo(afterDelivery.subtract(closed));
        CustomerInstallments.OpenInstallment deposit = customerInstallments.openOf(customer, 10).stream()
                .filter(i -> i.id().equals(signed.seq(1))).findFirst().orElseThrow();
        assertThat(deposit.reducedAmount()).isZero();
        assertThat(deposit.outstanding()).isEqualByComparingTo(deposit.amount());
    }
}
