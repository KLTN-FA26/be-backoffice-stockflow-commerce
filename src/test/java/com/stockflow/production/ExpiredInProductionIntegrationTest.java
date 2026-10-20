package com.stockflow.production;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.ReturnHeldStockCommand;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleRequests;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review of PR #109, item 8: blanks that expire while they are in the print shop (IN_PRODUCTION). They land nowhere
 * new - not back in storage, not in packing - and the policy trigger's generic refusal is replaced by a clear 409
 * STOCK_EXPIRED before anything is written. There is always a way out: a cancelled job writes the unprinted ones off
 * (EXPIRED, four-eyes approval); QC fails printed ones, which writes them off as scrap.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class ExpiredInProductionIntegrationTest {

    private static final String BLANK = ConfirmedDesigns.BLANK;
    private static final String PRINT_SHOP = "HCM-PRD01";

    @Autowired SampleRequests samples;
    @Autowired ProductionWorkflow workflow;
    @Autowired MaterialIssues issues;
    @Autowired PrintRuns runs;
    @Autowired InventoryService inventory;
    @Autowired ProductionOrders orders;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;
    private CurrentUser inspector;
    private CurrentUser manager;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 20);
        sales = user(Role.SALES_STAFF);
        printer = user(Role.PRODUCTION_STAFF);
        storekeeper = user(Role.WAREHOUSE_STAFF);
        inspector = user(Role.QC_STAFF);
        manager = user(Role.WAREHOUSE_MANAGER);
    }

    private CurrentUser user(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), DataScope.ALL, Set.of(), Set.of());
    }

    /**
     * A sample of 2 issued to the print shop, its blanks then found past their expiry. The blanks are a layer of
     * this test's own, the oldest of the SKU so FIFO hands it out, and a receipt time no other test shares: two
     * layers of one receipt merge in the print shop, and an expired one must not meet another test's.
     */
    private UUID issuedThenExpired() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at,
                    received_at)
                VALUES (?, ?, 'HCM-A02-1-A', 2, 0, 'AVAILABLE', 0, NOW(),
                    clock_timestamp() - interval '7000 days' - random() * interval '2000 days')""",
                Identifiers.newId(), BLANK));
        var design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, DemoData.CUSTOMER_ID);
        var sample = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        UUID order = samples.submit(sales, sample.id(), hcm).productionOrder().id();
        workflow.prepress(printer, order, true, null);
        for (int scans = 0; scans < 5 && status(order) == ProductionOrder.Status.READY; scans++) {
            issues.issue(storekeeper, order, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null));
        }
        assertThat(status(order)).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        // Time passing, not an edit anyone makes: the receipt's expiry date is immutable to the application and the
        // policy trigger, so the trigger is set aside for this one statement of the test's own transaction.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE inventory.stock_item SET expiry_date = CURRENT_DATE - 3
                     WHERE id IN (SELECT r.stock_item_id FROM inventory.stock_reservation r
                                   WHERE r.production_order_id = ? AND r.status = 'HELD')""", order);
        });
        return order;
    }

    private String layerOf(UUID hold) {
        return jdbc.queryForObject("""
                SELECT s.location_code || ':' || s.on_hand || ':' || s.status FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id WHERE r.id = ?""", String.class, hold);
    }

    private ProductionOrder.Status status(UUID order) {
        return orders.get(manager, order).summary().status();
    }

    @Test
    @DisplayName("a cancelled job writes off its expired unprinted blanks instead of returning them, and is not stuck")
    void cancellationWritesExpiredBlanksOff() throws Exception {
        UUID order = issuedThenExpired();
        int inStorageBefore = jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item
                 WHERE sku = ? AND status = 'AVAILABLE' AND location_code NOT LIKE '%-PRD%'""", Integer.class, BLANK);

        runs.cancel(manager, order, "The customer withdrew the sample");

        assertThat(status(order)).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item
                 WHERE sku = ? AND status = 'AVAILABLE' AND location_code NOT LIKE '%-PRD%'""", Integer.class, BLANK))
                .as("nothing expired went back on sale").isEqualTo(inStorageBefore);
        assertThat(jdbc.queryForList("""
                SELECT a.status FROM inventory.stock_adjustment a
                 WHERE a.reason_code = 'EXPIRED' AND a.note LIKE ?""", String.class,
                orders.get(manager, order).summary().orderNumber() + "%"))
                .as("the write-off is asked for, four eyes decide").isNotEmpty().allMatch("PENDING_APPROVAL"::equals);
    }

    @Test
    @DisplayName("expired goods in the print shop go neither to packing nor back to storage (409 STOCK_EXPIRED, nothing "
            + "written); QC fails them instead")
    void expiredGoodsAreRefusedEverywhereAndFailedByQc() throws Exception {
        UUID order = issuedThenExpired();
        runs.startPrinting(printer, order);
        runs.recordOutput(printer, order, 2, List.of());
        UUID hold = jdbc.queryForObject("""
                SELECT id FROM inventory.stock_reservation WHERE production_order_id = ? AND status = 'HELD'
                 ORDER BY reserved_at LIMIT 1""", UUID.class, order);
        String layerBefore = layerOf(hold);

        // Before the fix the policy trigger answered both with a generic "Invalid receipt/expiry date".
        Throwable toPacking = catchThrowable(() -> tx.executeWithoutResult(s -> inventory.finishHeld(
                new IssueReservedStockCommand(hold, null, order, "HCM-PACK01", manager.userId()))));
        Throwable toStorage = catchThrowable(() -> tx.executeWithoutResult(s -> inventory.returnHeld(
                new ReturnHeldStockCommand(hold, null, order, 1, "HCM-A02-1-A", manager.userId()))));
        for (Throwable refused : java.util.Arrays.asList(toPacking, toStorage)) {
            assertThat(refused).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STOCK_EXPIRED));
        }
        assertThat(layerOf(hold)).as("nothing moved").isEqualTo(layerBefore);

        // The way out: QC records them as failed, which writes them off as scrap; the order goes back for a reprint.
        ProductionOrders.Detail failed = runs.recordQc(inspector, order, 0, 2, "Blanks past their expiry", null);
        assertThat(failed.summary().status()).isNotEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0)::int FROM production.production_scrap
                 WHERE production_order_id = ? AND reason = 'QC_FAILED'""", Integer.class, order)).isEqualTo(2);
    }
}
