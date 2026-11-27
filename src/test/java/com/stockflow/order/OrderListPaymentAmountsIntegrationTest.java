package com.stockflow.order;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit bo-17 over HTTP: every row of the back-office order list carries {@code paidAmount}, {@code outstandingAmount}
 * and {@code overdueAmount}, read for the whole page in one batch - not one read of the installments per row.
 *
 * <p>One customer, three orders: one paid in full on signing, one signed and paid in part whose installment on signing
 * is past its due day, one DRAFT with no contract. The past due day is the installment's due day moved back, as time
 * passing would leave it ({@code OverdueInstallmentNoticeIntegrationTest} moves it the same way).</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class OrderListPaymentAmountsIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private UUID staff;
    private UUID customer;
    private UUID paid;
    private UUID overdue;
    private UUID draft;
    private BigDecimal overdueOwed;

    @BeforeEach
    void threeOrders() {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, 'Cong ty Tien Hang', ?, 'ACTIVE', 0, NOW())""", customer,
                "money-" + customer + "@example.com"));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);

        paid = orders.confirmed(customer, ConfirmedOrders.Line.cartons(sku, 10, 40_000)).orderId();

        var signed = orders.signed(customer, ConfirmedOrders.Line.cartons(sku, 4, 40_000));
        overdue = signed.orderId();
        UUID onSigning = signed.seq(1);
        orders.pay(customer, onSigning, new BigDecimal("1000000"));
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET due_date = due_date - 10 WHERE id = ?", onSigning);
        });
        overdueOwed = jdbc.queryForObject("SELECT amount - paid_amount - reduced_amount FROM payment.installment "
                + "WHERE id = ?", BigDecimal.class, onSigning);

        draft = orders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 2, 40_000)));
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private Map<String, Map<String, Object>> rows(Role role, int size) throws Exception {
        String body = http().asRole(role, staff, get("/api/v1/orders").param("customerId", customer.toString())
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(body, "$.data.items");
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        items.forEach(row -> byId.put((String) row.get("orderId"), row));
        return byId;
    }

    private BigDecimal installmentsTotal(UUID orderId) {
        return jdbc.queryForObject("SELECT SUM(amount) FROM payment.installment WHERE order_id = ?", BigDecimal.class,
                orderId);
    }

    private static BigDecimal money(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : new BigDecimal(value.toString());
    }

    @Test
    @DisplayName("a page of paid, overdue and no-contract orders: each row carries its paid, outstanding and overdue "
            + "amounts; the order without a contract has nulls")
    void amountsPerRow() throws Exception {
        var rows = rows(Role.SALES_MANAGER, 10);
        assertThat(rows).containsKeys(paid.toString(), overdue.toString(), draft.toString());

        var paidRow = rows.get(paid.toString());
        assertThat(money(paidRow, "paidAmount")).isEqualByComparingTo(installmentsTotal(paid));
        assertThat(money(paidRow, "outstandingAmount")).isEqualByComparingTo("0");
        assertThat(money(paidRow, "overdueAmount")).isEqualByComparingTo("0");

        var overdueRow = rows.get(overdue.toString());
        assertThat(money(overdueRow, "paidAmount")).isEqualByComparingTo("1000000");
        assertThat(money(overdueRow, "outstandingAmount"))
                .isEqualByComparingTo(installmentsTotal(overdue).subtract(new BigDecimal("1000000")));
        assertThat(money(overdueRow, "overdueAmount")).as("what the late installment still owes")
                .isEqualByComparingTo(overdueOwed).isPositive();

        var draftRow = rows.get(draft.toString());
        assertThat(List.of("paidAmount", "outstandingAmount", "overdueAmount")).allSatisfy(field ->
                assertThat(draftRow.get(field)).as(field + ": no contract, no figures").isNull());
    }

    @Test
    @DisplayName("the figures agree with GET /installments/orders/{orderId}")
    void agreesWithTheInstallmentsScreen() throws Exception {
        String body = http().asRole(Role.SALES_MANAGER, staff, get("/api/v1/installments/orders/" + overdue))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var row = rows(Role.SALES_MANAGER, 10).get(overdue.toString());
        assertThat(money(row, "paidAmount"))
                .isEqualByComparingTo(new BigDecimal(JsonPath.read(body, "$.data.paid").toString()));
        assertThat(money(row, "outstandingAmount"))
                .isEqualByComparingTo(new BigDecimal(JsonPath.read(body, "$.data.outstanding").toString()));
    }

    @Test
    @DisplayName("a reader of orders who does not read installments gets no money figures")
    void notForWhoDoesNotReadInstallments() throws Exception {
        var rows = rows(Role.PRODUCTION_COORDINATOR, 10);
        assertThat(rows).containsKey(overdue.toString());
        assertThat(List.of("paidAmount", "outstandingAmount", "overdueAmount")).allSatisfy(field ->
                assertThat(rows.get(overdue.toString()).get(field)).as(field).isNull());
    }

    @Test
    @DisplayName("the figures cost one statement per page: a page of three costs what a page of one does, and one "
            + "more than the same page without them")
    void oneStatementPerPage() throws Exception {
        long one = statements(Role.SALES_MANAGER, 1);
        long three = statements(Role.SALES_MANAGER, 3);
        assertThat(three).as("not one read per row").isEqualTo(one);
        long without = statements(Role.PRODUCTION_COORDINATOR, 3);
        assertThat(three - without).as("one batched read of the page's installments").isEqualTo(1);
    }

    /**
     * Statements the list prepares for a page of {@code size}. The statistics are the session factory's, which other
     * threads (listeners after commit) can add to: the least of a few runs is the request's own count.
     */
    private long statements(Role role, int size) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        rows(role, size);
        long least = Long.MAX_VALUE;
        statistics.setStatisticsEnabled(true);
        try {
            for (int run = 0; run < 3; run++) {
                statistics.clear();
                rows(role, size);
                least = Math.min(least, statistics.getPrepareStatementCount());
            }
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        return least;
    }
}
