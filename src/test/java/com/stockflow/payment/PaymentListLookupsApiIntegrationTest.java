package com.stockflow.payment;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The back office's lookups on the accountant's lists (UI/UX audit 2026-11-27), over HTTP on a contract signed through
 * the real flow ({@link ConfirmedOrders}), against the migrated database:
 * <ul>
 *   <li>bo-26: {@code /installments?outstanding=} keeps what still owes exactly that amount - the {@code outstanding}
 *       the rows show - with the other filters;</li>
 *   <li>bo-27: {@code /installments?search=} also finds a contract number or an order number, beside the customer's
 *       name and tax code;</li>
 *   <li>bo-24: {@code /payment-receipts?search=} finds receipts by customer, contract number or order number, its total
 *       and its file taking the same filter, and each line of the list names the installment it paid - read once for
 *       the page, the statements the list runs not growing with its rows.</li>
 * </ul>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class PaymentListLookupsApiIntegrationTest {

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
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CustomerService customers;

    private ConfirmedOrders orders;
    private UUID accountant;
    /** Unique to this run, so a search finds this test's customer and no other. */
    private String token;
    private UUID customer;
    private ConfirmedOrders.Signed signed;

    @BeforeEach
    void aSignedContract() {
        orders = new ConfirmedOrders(context);
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        token = "Lookup" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 41), 36).toUpperCase();
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", customer, "Công ty In Ly " + token,
                "lookups-" + customer + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        // Paying after delivery is credit: approved by accounting, ample, so signing holds nothing for it.
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000000"), 30,
                "ample credit", accountant, null));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        signed = orders.signed(customer, HALF_AND_HALF, ConfirmedOrders.Line.cartons(sku, 10, 40_000));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return new SecuredMvc(mvc, decoder).asRole(Role.ACCOUNTANT, accountant, request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<Map<String, Object>> rows(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(body(request.param("size", "200")), "$.data.items");
    }

    private static List<Object> field(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(r -> r.get(name)).toList();
    }

    private BigDecimal outstanding(UUID installmentId) {
        return jdbc.queryForObject("SELECT amount - paid_amount - reduced_amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, installmentId);
    }

    // ---- installments -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("bo-26: outstanding keeps what still owes exactly that amount, as the rows show it, with other filters")
    void installmentsByOutstanding() throws Exception {
        orders.pay(customer, signed.seq(1), new BigDecimal("1000"));
        BigDecimal deposit = outstanding(signed.seq(1));
        BigDecimal afterDelivery = outstanding(signed.seq(2));
        assertThat(deposit).isNotEqualByComparingTo(afterDelivery);
        String typed = token.toLowerCase();

        List<Map<String, Object>> owingDeposit = rows(get("/api/v1/installments").param("search", typed)
                .param("outstanding", deposit.toPlainString()));
        assertThat(field(owingDeposit, "installmentId")).containsExactly(signed.seq(1).toString());
        assertThat(new BigDecimal(owingDeposit.getFirst().get("outstanding").toString()))
                .as("the filter and the field are one definition").isEqualByComparingTo(deposit);
        assertThat(field(rows(get("/api/v1/installments").param("search", typed)
                .param("outstanding", afterDelivery.setScale(4).toPlainString())), "installmentId"))
                .as("compared as numbers: the scale typed does not matter")
                .containsExactly(signed.seq(2).toString());
        assertThat(rows(get("/api/v1/installments").param("search", typed)
                .param("outstanding", afterDelivery.toPlainString()).param("owing", "true")))
                .as("with owing=true: the installment after delivery is not due yet").isEmpty();
        assertThat(rows(get("/api/v1/installments").param("customerId", customer.toString())
                .param("outstanding", deposit.add(BigDecimal.ONE).toPlainString()))).isEmpty();

        // Each row found by an amount says it still owes that amount - across every customer, not only this one.
        assertThat(rows(get("/api/v1/installments").param("outstanding", deposit.toPlainString())))
                .isNotEmpty().allSatisfy(row -> assertThat(new BigDecimal(row.get("outstanding").toString()))
                        .isEqualByComparingTo(deposit));
    }

    @Test
    @DisplayName("bo-27: search finds a contract number or an order number too, any case; the customer search stays")
    void installmentsByContractOrOrderNumber() throws Exception {
        List<String> both = List.of(signed.seq(1).toString(), signed.seq(2).toString());

        String contract = signed.contractNumber().toLowerCase();
        List<Map<String, Object>> byContract = rows(get("/api/v1/installments").param("search", contract));
        assertThat(field(byContract, "installmentId")).containsAll(both);
        assertThat(byContract).allSatisfy(row -> assertThat(row.get("contractNumber").toString().toLowerCase())
                .contains(contract));

        String order = signed.orderNumber().substring(3).toLowerCase();
        List<Map<String, Object>> byOrder = rows(get("/api/v1/installments").param("search", order));
        assertThat(field(byOrder, "installmentId")).containsAll(both);
        assertThat(byOrder).allSatisfy(row -> assertThat(row.get("orderNumber").toString().toLowerCase())
                .contains(order));

        assertThat(field(rows(get("/api/v1/installments").param("search", token.toLowerCase())), "installmentId"))
                .as("the customer's name still finds them").containsExactlyInAnyOrderElementsOf(both);
        assertThat(rows(get("/api/v1/installments").param("search", signed.contractNumber())
                .param("customerId", customer.toString()).param("milestone", "ON_SIGNING")))
                .as("with the other filters").extracting(r -> r.get("installmentId"))
                .containsExactly(signed.seq(1).toString());
        assertThat(rows(get("/api/v1/installments").param("search", "khong-co-so-nao-" + token)))
                .as("a term matching no customer and no number finds nothing, never everything").isEmpty();
    }

    /** The statements one call of the receipts list, a page of {@code size}, prepared. */
    private long statements(Statistics stats, String size) throws Exception {
        stats.clear();
        body(get("/api/v1/payment-receipts").param("customerId", customer.toString()).param("size", size));
        return stats.getPrepareStatementCount();
    }

    // ---- receipts ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("bo-24: search finds receipts by customer, contract or order number; the total and file take it too")
    void receiptsBySearch() throws Exception {
        PaymentReceipt receipt = orders.pay(customer, signed.seq(1), new BigDecimal("1000"));
        String id = receipt.id().toString();

        for (String term : List.of(token.toLowerCase(), signed.contractNumber().toLowerCase(),
                signed.orderNumber().toLowerCase())) {
            assertThat(field(rows(get("/api/v1/payment-receipts").param("search", term)), "receiptId"))
                    .as("found by %s", term).containsExactly(id);
        }
        assertThat(field(rows(get("/api/v1/payment-receipts").param("search", signed.contractNumber())
                .param("reference", receipt.reference())), "receiptId"))
                .as("reference stays its exact filter, beside the search").containsExactly(id);
        assertThat(rows(get("/api/v1/payment-receipts").param("search", signed.contractNumber())
                .param("reference", "FT-NOT-" + token))).isEmpty();
        assertThat(rows(get("/api/v1/payment-receipts").param("search", "khong-co-so-nao-" + token)))
                .as("matching nothing finds nothing").isEmpty();
        assertThat(rows(get("/api/v1/payment-receipts").param("search", "50%_" + token)))
                .as("a typed % or _ is literal, not a wildcard").isEmpty();

        String summary = body(get("/api/v1/payment-receipts/summary").param("search", signed.contractNumber()));
        assertThat(((Number) JsonPath.read(summary, "$.data.count")).longValue()).isEqualTo(1L);
        assertThat(new BigDecimal(JsonPath.read(summary, "$.data.totalAmount").toString()))
                .isEqualByComparingTo("1000");
        byte[] file = new SecuredMvc(mvc, decoder).asRole(Role.ACCOUNTANT, accountant,
                        get("/api/v1/payment-receipts/export").param("search", signed.orderNumber()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(file).as("the export runs the search in its own SQL").isNotEmpty();
    }

    @Test
    @DisplayName("bo-24: each line of the list names its installment, read once for the page: flat statement count")
    void receiptLinesNameTheirInstallment() throws Exception {
        for (int i = 0; i < 3; i++) {
            orders.pay(customer, signed.seq(1), new BigDecimal("1000"));
        }

        List<Map<String, Object>> receipts = rows(get("/api/v1/payment-receipts")
                .param("customerId", customer.toString()));
        assertThat(receipts).hasSize(3);
        assertThat(receipts).allSatisfy(row -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) row.get("lines");
            assertThat(lines).singleElement().satisfies(line -> {
                assertThat(line).containsEntry("installmentId", signed.seq(1).toString())
                        .containsEntry("contractNumber", signed.contractNumber())
                        .containsEntry("orderId", signed.orderId().toString())
                        .containsEntry("orderNumber", signed.orderNumber())
                        .containsEntry("seq", 1)
                        .containsEntry("name", "Dat coc")
                        .containsEntry("milestone", "ON_SIGNING");
            });
        });

        // Statistics are the SessionFactory's, so the listeners still handling the receipts just recorded may add
        // statements of their own to a measurement; noise only ever adds, so the least of a few rounds is the list's.
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean was = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            // Warmed first: a name or label a cache keeps after the first call must not pass for a flat count.
            statements(stats, "3");
            long one = Long.MAX_VALUE;
            long three = Long.MAX_VALUE;
            for (int round = 0; round < 4; round++) {
                one = Math.min(one, statements(stats, "1"));
                three = Math.min(three, statements(stats, "3"));
            }
            assertThat(three).as("a page of three runs the statements of a page of one").isEqualTo(one);
        } finally {
            stats.setStatisticsEnabled(was);
        }
    }
}
