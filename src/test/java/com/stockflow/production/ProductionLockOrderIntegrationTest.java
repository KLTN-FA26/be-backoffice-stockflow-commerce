package com.stockflow.production;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of PR #109, item 4: every path that moves or releases stock takes the SKU's advisory lock first and the stock
 * rows second, as a checkout does. A production path that locked a stock row first and asked for the SKU lock after
 * could meet a checkout holding the SKU lock and wanting that row: a deadlock (40P01).
 *
 * <p>Each test plays the checkout with a raw connection holding the SKU lock, starts the production step, waits until
 * that step is blocked on the advisory lock, then asks for the stock row with {@code NOWAIT}: it must be free. Against
 * the old order the step already held the row, and {@code NOWAIT} failed with 55P03.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class ProductionLockOrderIntegrationTest {

    private static final String BLANK = ConfirmedDesigns.BLANK;

    @Autowired SampleRequests samples;
    @Autowired ProductionWorkflow workflow;
    @Autowired MaterialIssues issues;
    @Autowired InventoryService inventory;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired DataSource dataSource;

    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 20);
        sales = user(Role.SALES_STAFF);
        printer = user(Role.PRODUCTION_STAFF);
        storekeeper = user(Role.WAREHOUSE_STAFF);
    }

    private CurrentUser user(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), DataScope.ALL, Set.of(), Set.of());
    }

    /** A sample round submitted to HCM and through prepress: its blanks are production holds, ready to issue. */
    private UUID readySample() throws Exception {
        var design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, DemoData.CUSTOMER_ID);
        var sample = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        UUID order = samples.submit(sales, sample.id(), hcm).productionOrder().id();
        workflow.prepress(printer, order, true, null);
        return order;
    }

    private UUID heldLayer(UUID productionOrderId) {
        return jdbc.queryForList("""
                SELECT stock_item_id FROM inventory.stock_reservation
                 WHERE production_order_id = ? AND status = 'HELD' ORDER BY reserved_at""", UUID.class,
                productionOrderId).getFirst();
    }

    @Test
    @DisplayName("issuing blanks waits for the SKU lock before it locks a stock row")
    void issueTakesTheSkuLockFirst() throws Exception {
        UUID order = readySample();
        assertSkuLockComesFirst(heldLayer(order), () -> issues.issue(storekeeper, order,
                new MaterialIssues.Issue(UUID.randomUUID(), "HCM-PRD01", null)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_material_issue"
                + " WHERE production_order_id = ?", Integer.class, order)).as("the scan went through after").isEqualTo(1);
    }

    @Test
    @DisplayName("releasing a production order's holds waits for the SKU lock before it locks a stock row")
    void releaseTakesTheSkuLockFirst() throws Exception {
        UUID order = readySample();
        int[] released = new int[1];
        assertSkuLockComesFirst(heldLayer(order),
                () -> released[0] = tx.execute(s -> inventory.releaseProductionHolds(order)));
        assertThat(released[0]).isEqualTo(2);
    }

    private void assertSkuLockComesFirst(UUID layer, Runnable productionStep) throws Exception {
        try (Connection checkout = dataSource.getConnection()) {
            checkout.setAutoCommit(false);
            try (PreparedStatement lock = checkout.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 701))")) {
                lock.setString(1, BLANK);
                lock.execute();
            }
            CompletableFuture<Void> step = CompletableFuture.runAsync(productionStep);
            Await.until("the production step waits for the SKU lock", Duration.ofSeconds(15), () -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'""", Integer.class) > 0);
            assertThat(step).as("still waiting while the checkout holds the SKU").isNotDone();
            // The checkout now wants the row the production step will move. Free, so no cycle can form.
            try (PreparedStatement row = checkout.prepareStatement(
                    "SELECT id FROM inventory.stock_item WHERE id = ? FOR UPDATE NOWAIT")) {
                row.setObject(1, layer);
                row.execute();
            }
            checkout.rollback();
            step.get(30, TimeUnit.SECONDS);
        }
    }
}
