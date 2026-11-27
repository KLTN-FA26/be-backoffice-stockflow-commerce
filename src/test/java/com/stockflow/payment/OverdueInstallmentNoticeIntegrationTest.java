package com.stockflow.payment;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.contracts.InstallmentOverdue;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-7 (WF4-OVERDUE-*; kltn-docs 15 §4.3 step 11) against the real database and event plumbing: an installment that
 * turned overdue is announced by the daily scan once, however often the scan runs, to whoever looks after the customer
 * (since 2026-11-28, 15 BR-22: its owner - no longer every holder of the hold permission); the scan reads back only a few days; and whoever is about to release the customer's order is warned when the
 * dialog opens, the release itself unaffected.
 *
 * <p>The contract is drawn up, sent and signed through {@link SalesContracts} (never inserted), sent six days ago and
 * signed five days ago, so the installment on signing fell due three days ago - the one way to a past due day without a
 * fake clock, as {@code CreditPositionApiIntegrationTest} does it. Other tests leave overdue installments of their own
 * in the same database, so every assertion is about this installment's id.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class OverdueInstallmentNoticeIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CustomerService customers;
    @Autowired SalesContracts contracts;
    @Autowired InstallmentService installments;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private StaffFixtures staff;
    private UUID customer;
    private UUID orderId;
    private UUID installmentId;
    private UUID salesA;
    private UUID salesB;
    private UUID salesLocked;
    private UUID accountant;
    private UUID warehouseManager;

    @BeforeEach
    void anOverdueInstallment() {
        confirmedOrders = new ConfirmedOrders(context);
        staff = new StaffFixtures(jdbc, tx, entityManager);
        salesA = staff.staff("SALES_STAFF");
        salesB = staff.staff("SALES_STAFF");
        salesLocked = staff.staff("SALES_STAFF");
        staff.setStatus(salesLocked, "LOCKED");
        accountant = staff.staff("ACCOUNTANT");
        warehouseManager = staff.staff("WAREHOUSE_MANAGER", staff.hcm());
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Overdue customer', ?, 'ACTIVE', 0, NOW())""", customer, user,
                "overdue-" + customer + "@example.com"));
        // 15 BR-22: Sales A owns the customer and is the one told.
        new com.stockflow.support.SalesOwners(context).own(customer, salesA);
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("50000000"), 30, "test terms",
                confirmedOrders.accountant(), null));
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        orderId = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, 40_000)));
        confirmedOrders.legalProfile(customer);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        // Strict mode (14 BR-25): the customer's owner draws its contract up.
        var sales = com.stockflow.support.SalesOwners.caller(salesA, com.stockflow.common.security.Role.SALES_STAFF);
        SalesContracts.ContractView sent = WithCurrentUser.call(sales, DataScope.ALL, () -> {
            SalesContracts.ContractView drafted = contracts.create(sales, orderId, new SalesContracts.Draft(null,
                    List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                            List.of(new SalesContracts.BatchLine(line, 10)))),
                    List.of(share("ON_SIGNING", "30", 2), share("AFTER_DELIVERY", "70", 30))));
            return contracts.send(sales, orderId, drafted.version());
        });
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.sales_contract SET sent_at = sent_at - INTERVAL '6 days' WHERE id = ?",
                    sent.id());
        });
        byte[] scan = TestPdfs.blank();
        WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today.minusDays(5)));
        installmentId = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND seq = 1",
                UUID.class, orderId);
    }

    private static SalesContracts.Installment share(String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(milestone, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private long told(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? "
                + "AND source_type = 'INSTALLMENT' AND source_id = ?", Long.class, user, installmentId.toString());
    }

    /** Events are handled in order: when a marker published after the one under test has been told, the one before has
     *  been seen too. */
    private void afterMarker() {
        UUID marker = UUID.randomUUID();
        // Published in a transaction: a listener of @ApplicationModuleListener runs after its commit.
        tx.executeWithoutResult(s -> publisher.publishEvent(new InstallmentOverdue(marker, UUID.randomUUID(),
                "HD-MARKER", UUID.randomUUID(), "SO-MARKER", customer, 1, LocalDate.now(), BigDecimal.ONE,
                "VND")));
        Await.until("the marker is told", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.user_notification WHERE recipient_id = ? AND source_id = ?", Long.class, salesA,
                marker.toString()) >= 1);
    }

    @Test
    @DisplayName("15 BR-22: the scan tells the customer's owner once; a second run, another salesperson and the other "
            + "roles get nothing more or nothing at all")
    void salesAreToldOncePerInstallment() {
        installments.announceNewlyOverdue();
        Await.until("Sales A is told", ASYNC, () -> told(salesA) >= 1);

        installments.announceNewlyOverdue();
        afterMarker();

        assertThat(told(salesA)).as("run twice, told once").isEqualTo(1);
        assertThat(told(salesB)).as("D6: not every salesperson").isZero();
        assertThat(told(salesLocked)).as("a locked account is not told").isZero();
        assertThat(told(accountant)).as("accounting does not hold orders by hand").isZero();
        assertThat(told(warehouseManager)).isZero();
        assertThat(jdbc.queryForMap("SELECT category, link, severity FROM notification.user_notification WHERE "
                + "recipient_id = ? AND source_id = ?", salesA, installmentId.toString()))
                .containsEntry("category", "PAYMENT_OVERDUE").containsEntry("link", "/admin/orders/" + orderId);
    }

    @Test
    @DisplayName("WF-05 BE-49: the scan writes down the due date it announced; the same installment overdue again on a "
            + "new due date is announced again, once")
    void aNewDueDateIsAnnouncedAgain() {
        installments.announceNewlyOverdue();
        Await.until("Sales A is told", ASYNC, () -> told(salesA) >= 1);
        assertThat(jdbc.queryForObject("SELECT overdue_announced_for = due_date FROM payment.installment WHERE id = ?",
                Boolean.class, installmentId)).as("the due date announced is written down").isTrue();

        // Its due date moves a day back - as a corrected delivery moves an installment after delivery - and it is
        // still overdue. Written below the domain, its triggers off: an installment on signing is never moved by the
        // application, only one after delivery is.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET due_date = due_date - 1 WHERE id = ?", installmentId);
        });
        installments.announceNewlyOverdue();
        Await.until("Sales A is told again", ASYNC, () -> told(salesA) >= 2);

        installments.announceNewlyOverdue();
        afterMarker();
        assertThat(told(salesA)).as("once per due date").isEqualTo(2);
    }

    @Test
    @DisplayName("the release dialog is warned that the customer is overdue when it opens; the other roles are refused")
    void theReleaseDialogIsWarned() throws Exception {
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        String path = "/api/v1/orders/" + orderId + "/release-warnings";
        shipsFromHcm(orderId);

        http.asRole(Role.WAREHOUSE_MANAGER, warehouseManager, get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditWarning.outcome").value("OVERDUE"));
        http.asRole(Role.ACCOUNTANT, accountant, get(path)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_MANAGER, warehouseManager,
                get("/api/v1/orders/" + UUID.randomUUID() + "/release-warnings")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no warning for a customer who owes nothing overdue")
    void noWarningWithoutOverdue() throws Exception {
        UUID calmCustomer = Identifiers.newId();
        UUID calmUser = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Calm customer', ?, 'ACTIVE', 0, NOW())""", calmCustomer, calmUser,
                "calm-" + calmCustomer + "@example.com"));
        confirmedOrders.legalProfile(calmCustomer);
        UUID calmOrder = confirmedOrders.signed(calmCustomer, ConfirmedOrders.Line.cartons(
                CustomerSkuRows.issue(jdbc, tx, entityManager, calmCustomer, 50), 10, 40_000)).orderId();
        shipsFromHcm(calmOrder);

        new SecuredMvc(mvc, decoder).asRole(Role.WAREHOUSE_MANAGER, warehouseManager,
                        get("/api/v1/orders/" + calmOrder + "/release-warnings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditWarning").doesNotExist());
    }

    /**
     * The order, signed, ships from HCM, the warehouse manager's own: a warehouse manager reads the release dialog only
     * for an order of their warehouses (BR-SEC-002), before it is released, so by its shipping warehouse. Set below the
     * service: Sales choose it once the order is confirmed, and this order's installment is left unpaid on purpose.
     */
    private void shipsFromHcm(UUID order) {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ordering.customer_order SET shipping_warehouse_id = ?, shipping_warehouse_chosen_at = NOW(),
                       shipping_warehouse_chosen_by = ? WHERE id = ?""", staff.hcm(),
                confirmedOrders.sales().userId(), order));
    }
}
