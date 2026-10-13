package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.repository.StockAlertDirtyRepository;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.support.AlertFixtures.HCM_BIN;
import static com.stockflow.support.AlertFixtures.HCM_BIN_2;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Low-stock alerts end to end (SCRUM-147, 165): real stock writes (adjustment, receipt and putaway, a raw UPDATE) mark the
 * SKU dirty through the trigger, the drain evaluates it, the event reaches the inbox listener, and the planner is told
 * once. Thresholds, recovery, the re-touch window, once per day, the nightly reconcile and the policy-change listener.
 */
@SecuredIntegrationTest
class StockAlertIntegrationTest {

    @Autowired AlertDirtyDrainer drainer;
    @Autowired AlertReconciler reconciler;
    @Autowired StockAlertDirtyRepository dirty;
    @Autowired StockAlertRepository alerts;
    @Autowired StockOperations operations;
    @Autowired InventoryService inventory;
    @Autowired InventoryControlService controls;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;

    private AlertFixtures fixtures;
    private UUID planner;
    private UUID clerk;
    private UUID manager;
    private UUID customer;
    private String sku;
    private UUID bin;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        awaitEventsDelivered();
        drainer.drain();
        planner = fixtures.staff("INVENTORY_PLANNER");
        clerk = fixtures.staff("WAREHOUSE_STAFF", fixtures.hcm());
        manager = fixtures.staff("WAREHOUSE_MANAGER", fixtures.hcm());
        customer = fixtures.staff("CUSTOMER");
        sku = fixtures.sku(10, null);
        bin = fixtures.stock(sku, HCM_BIN, 50, "AVAILABLE", null, null);
        drainer.drain();
        awaitEventsDelivered();
        assertThat(open(Kind.REORDER)).as("50 on hand, reorder point 10").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void awaitEventsDelivered() {
        Await.until("alert events delivered", Duration.ofSeconds(30), () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL
                  AND (event_type LIKE '%InventoryAlertsRaised' OR event_type LIKE '%SkuInventoryControlChanged')""",
                Long.class) == 0);
    }

    private Optional<StockAlert> open(Kind kind) {
        return tx.execute(s -> alerts.findOpen(Key.stockLevel(kind, sku)));
    }

    private long episodes(Kind kind) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert WHERE sku = ? AND kind = ?", Long.class, sku, kind.name());
    }

    private long announcements() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE '%InventoryAlertsRaised'"
                + " AND serialized_event LIKE ?", Long.class, "%" + sku + "%") / listenersPerEvent();
    }

    private long listenersPerEvent() {
        return 1;
    }

    /** A real stock write: an adjustment asked for by the clerk and approved by someone else. */
    private void adjust(int delta) {
        var asked = operations.requestAdjustment(new RequestAdjustmentCommand(HCM_BIN, new Sku(sku), null, delta,
                delta < 0 ? StockAdjustmentReason.LOST : StockAdjustmentReason.FOUND, "test", clerk));
        operations.approve(asked.adjustmentId(), manager);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE id = ?", Integer.class, bin);
    }

    private long dirtyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert_dirty WHERE sku = ?", Long.class, sku);
    }

    private void awaitPlannerMessages(long expected) {
        Await.until("planner has " + expected + " message(s)", Duration.ofSeconds(30), () -> fixtures.messages(planner) >= expected);
    }

    // ------------------------------------------------------------------ the trigger

    @Test
    void everyKindOfStockWriteMarksTheSkuDirtyAndAWriteThatChangesNothingDoesNot() {
        drainer.drain();
        assertThat(dirtyRows()).isZero();

        adjust(-1);                                                    // an approved adjustment
        assertThat(dirtyRows()).isEqualTo(1);
        drainer.drain();

        fixtures.setOnHand(bin, 40);                                   // any writer at all
        assertThat(dirtyRows()).isEqualTo(1);
        drainer.drain();

        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_item SET last_modified_by = 'x', reserved = reserved WHERE id = ?", bin));
        assertThat(dirtyRows()).as("nothing the thresholds read changed").isZero();

        fixtures.stock(sku, HCM_BIN_2, 5, "QUARANTINE", null, null);   // a new row
        assertThat(dirtyRows()).isEqualTo(1);
        drainer.drain();

        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM inventory.stock_item WHERE sku = ? AND location_code = ?", sku, HCM_BIN_2));
        assertThat(dirtyRows()).isEqualTo(1);
    }

    @Test
    void aHundredWritesToOneSkuAreOneEvaluation() {
        drainer.drain();
        for (int i = 0; i < 100; i++) {
            fixtures.setOnHand(bin, 50 - (i % 2));
        }
        assertThat(dirtyRows()).isGreaterThan(50);
        drainer.drain();
        assertThat(dirtyRows()).isZero();
    }

    // ------------------------------------------------------------------ opening, once

    @Test
    void aStockWriteBelowTheThresholdProducesOneAlertAndExactlyOneMessagePerPlanner() {
        adjust(-42);
        assertThat(onHand()).isEqualTo(8);
        drainer.drain();

        StockAlert alert = open(Kind.REORDER).orElseThrow();
        assertThat(alert.status()).isEqualTo(StockAlert.Status.OPEN);
        assertThat(alert.observedQty()).isEqualTo(8);
        assertThat(alert.thresholdQty()).isEqualTo(10);
        assertThat(alert.warehouseId()).isNull();
        assertThat(open(Kind.SAFETY)).isEmpty();

        awaitPlannerMessages(1);
        assertThat(fixtures.messages(planner)).isEqualTo(1);
        assertThat(fixtures.messages(planner, "REORDER")).isEqualTo(1);
        // The warehouse people, a customer: nothing.
        assertThat(fixtures.messages(clerk)).isZero();
        assertThat(fixtures.messages(manager)).isZero();
        assertThat(fixtures.messages(customer)).isZero();
    }

    @Test
    void anotherWriteWhileTheAlertIsOpenRefreshesItAndTellsNobodyAgain() {
        adjust(-42);
        drainer.drain();
        awaitPlannerMessages(1);
        long before = announcements();

        adjust(-3);
        drainer.drain();
        awaitEventsDelivered();

        assertThat(episodes(Kind.REORDER)).isEqualTo(1);
        assertThat(open(Kind.REORDER).orElseThrow().observedQty()).isEqualTo(5);
        assertThat(announcements()).isEqualTo(before);
        assertThat(fixtures.messages(planner)).isEqualTo(1);
    }

    @Test
    void stockExactlyAtTheReorderPointIsLowAndOneAboveIsNot() {
        fixtures.setOnHand(bin, 11);
        drainer.drain();
        assertThat(open(Kind.REORDER)).isEmpty();
        fixtures.setOnHand(bin, 10);
        drainer.drain();
        assertThat(open(Kind.REORDER)).isPresent();
    }

    @Test
    void belowTheSafetyStockAndAtTheReorderPointOpenTwoAlertsAndTwoMessages() {
        fixtures.setThresholds(sku, 10, 9);
        adjust(-42);                      // 8: at or below the reorder point and below the safety stock
        drainer.drain();
        assertThat(open(Kind.REORDER)).isPresent();
        assertThat(open(Kind.SAFETY)).isPresent();
        awaitPlannerMessages(2);
        assertThat(fixtures.messages(planner, "REORDER")).isEqualTo(1);
        assertThat(fixtures.messages(planner, "SAFETY")).isEqualTo(1);
    }

    @Test
    void reservationsDoNotCountAndOnlyAvailableUnexpiredStockDoes() {
        // 50 on hand, all held by an order: usable on-hand is still 50 (not ATP), so no alert.
        fixtures.hold(bin, 50);
        drainer.drain();
        assertThat(open(Kind.REORDER)).isEmpty();
        // Stock in quarantine, damaged, or expired does not count towards the level.
        fixtures.stock(sku, HCM_BIN_2, 500, "QUARANTINE", null, null);
        fixtures.stock(sku, "HCM-B01-1-A", 500, "DAMAGED", null, null);
        fixtures.stock(sku, "HCM-B01-1-B", 500, "AVAILABLE", "LOT-OLD", LocalDate.now().minusDays(1));
        fixtures.release(bin);
        fixtures.setOnHand(bin, 9);
        drainer.drain();
        assertThat(open(Kind.REORDER).orElseThrow().observedQty()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void aReceiptAndPutawayThatRaiseStockAboveTheThresholdRecoverTheAlertWithoutAMessage() {
        adjust(-42);
        drainer.drain();
        awaitPlannerMessages(1);
        // Postgres keeps microseconds; Linux clocks give nanoseconds, and the layer is found by its receipt time.
        Instant receivedAt = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID line = UUID.randomUUID();
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), null, null, "HCM-RCV01", 100, clerk, receivedAt));
        drainer.drain();
        // INBOUND stock is not usable yet: the alert stays open.
        assertThat(open(Kind.REORDER)).isPresent();

        inventory.reclassify(new ReclassifyStockCommand(new Sku(sku), null, "HCM-RCV01", HCM_BIN_2, 100,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, UUID.randomUUID(), clerk, null, receivedAt));
        drainer.drain();
        awaitEventsDelivered();

        assertThat(open(Kind.REORDER)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT resolution FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER'", String.class, sku))
                .isEqualTo("RECOVERED");
        assertThat(fixtures.messages(planner)).as("recovery says nothing").isEqualTo(1);
    }

    @Test
    void anAcknowledgedAlertRecoversToo() {
        adjust(-42);
        drainer.drain();
        StockAlert alert = open(Kind.REORDER).orElseThrow();
        tx.executeWithoutResult(s -> {
            StockAlert fresh = alerts.findByIdForUpdate(alert.id()).orElseThrow();
            fresh.acknowledge(manager, null, Instant.now());
            alerts.update(fresh);
        });
        fixtures.setOnHand(bin, 90);
        drainer.drain();
        assertThat(jdbc.queryForObject("SELECT status || '/' || resolution FROM inventory.stock_alert WHERE id = ?", String.class, alert.id()))
                .isEqualTo("RESOLVED/RECOVERED");
    }

    // ------------------------------------------------------------------ the re-touch window and once per day

    @Test
    void aTouchBackBelowWithinSixHoursOpensTheRowButDoesNotAnnounceAndAfterSixHoursItDoesButTheDayKeyKeepsItToOneMessage() {
        adjust(-42);
        drainer.drain();
        awaitPlannerMessages(1);
        assertThat(announcements()).isEqualTo(1);

        // Recovers, then falls again straight away: a new episode, quietly.
        fixtures.setOnHand(bin, 50);
        drainer.drain();
        fixtures.setOnHand(bin, 4);
        drainer.drain();
        awaitEventsDelivered();
        assertThat(episodes(Kind.REORDER)).isEqualTo(2);
        assertThat(open(Kind.REORDER)).isPresent();
        assertThat(announcements()).as("inside the 6 hour window").isEqualTo(1);

        // Recovers again; its end is moved 7 hours into the past; falls again: announced.
        fixtures.setOnHand(bin, 50);
        drainer.drain();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_alert SET resolved_at = ?, opened_at = LEAST(opened_at, ?),"
                + " last_observed_at = LEAST(last_observed_at, ?) WHERE sku = ? AND kind = 'REORDER' AND status = 'RESOLVED'",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(7))),
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(8))),
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(8))), sku));
        fixtures.setOnHand(bin, 3);
        drainer.drain();
        awaitEventsDelivered();
        assertThat(episodes(Kind.REORDER)).isEqualTo(3);
        assertThat(announcements()).as("after the window").isEqualTo(2);

        // ... but the inbox says it once per kind, warehouse, recipient and day.
        assertThat(fixtures.messages(planner, "REORDER")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ thresholds changing

    @Test
    void savingTheThresholdsEvaluatesTheSkuAtOnceWithoutWaitingForTheDrain() {
        String own = fixtures.sku(null, null);
        fixtures.stock(own, HCM_BIN, 30, "AVAILABLE", null, null);
        drainer.drain();
        long version = controls.item(new Sku(own)).version();

        tx.executeWithoutResult(s -> controls.configure(new Sku(own), version,
                new InventoryPolicy(40, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null)));

        Await.until("the alert from the policy-change listener", Duration.ofSeconds(30), () -> jdbc.queryForObject(
                "SELECT count(*) FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER' AND status = 'OPEN'", Long.class, own) == 1);
    }

    @Test
    void removingTheThresholdClosesTheAlertAsUnconfiguredWithoutAMessage() {
        adjust(-42);
        drainer.drain();
        awaitPlannerMessages(1);
        fixtures.setThresholds(sku, null, null);
        reconciler.reconcile();
        awaitEventsDelivered();
        assertThat(open(Kind.REORDER)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT resolution FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER'", String.class, sku))
                .isEqualTo("UNCONFIGURED");
        assertThat(fixtures.messages(planner)).isEqualTo(1);
    }

    @Test
    void theNightlyReconcileFindsALowSkuWhoseTriggerNeverFired() {
        // A threshold edited with SQL queues nothing; stock is 50, so raise the reorder point above it.
        fixtures.setThresholds(sku, 60, null);
        assertThat(dirtyRows()).isZero();
        assertThat(open(Kind.REORDER)).isEmpty();

        int evaluated = reconciler.reconcile();

        assertThat(evaluated).isGreaterThanOrEqualTo(1);
        assertThat(open(Kind.REORDER).orElseThrow().observedQty()).isEqualTo(50);
        awaitPlannerMessages(1);
    }

    @Test
    void theNightlyReconcileAlsoClosesAlertsOfSkusThatAreNoLongerLow() {
        adjust(-42);
        drainer.drain();
        // Stock comes back with no trigger noticed (queue lost): the reconcile still finds the open alert.
        tx.executeWithoutResult(s -> jdbc.update("ALTER TABLE inventory.stock_item DISABLE TRIGGER trg_stock_item_dirty_update"));
        try {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_item SET on_hand = 80 WHERE id = ?", bin));
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("ALTER TABLE inventory.stock_item ENABLE TRIGGER trg_stock_item_dirty_update"));
        }
        assertThat(dirtyRows()).isZero();
        reconciler.reconcile();
        assertThat(open(Kind.REORDER)).isEmpty();
    }

    // ------------------------------------------------------------------ one definition of low stock (plan A8)

    @Test
    void lowStockBatchReturnsTheSameSkusInOrderAndPagesByTheLastOne() {
        String prefix = "LSB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String a = fixtures.sku(prefix + "-1", 10, null);
        String b = fixtures.sku(prefix + "-2", null, 10);
        String c = fixtures.sku(prefix + "-3", 5, null);
        String fine = fixtures.sku(prefix + "-4", 5, 5);
        fixtures.stock(a, HCM_BIN, 10, "AVAILABLE", null, null);     // at the reorder point
        fixtures.stock(b, HCM_BIN, 9, "AVAILABLE", null, null);      // no reorder point, below the safety stock
        fixtures.stock(c, HCM_BIN, 0, "AVAILABLE", null, null);      // nothing left
        fixtures.stock(fine, HCM_BIN, 5, "AVAILABLE", null, null);   // 6 units against 5 and 5: not low
        fixtures.stock(fine, HCM_BIN_2, 1, "AVAILABLE", null, null);
        String start = prefix + "-0";

        List<LowStockItem> first = inventory.lowStockBatch(start, 3);
        assertThat(first).extracting(LowStockItem::sku).containsExactly(a, b, c);
        assertThat(first.get(0).reorderRequired()).isTrue();
        assertThat(first.get(0).belowSafetyStock()).isFalse();
        assertThat(first.get(1).reorderRequired()).as("no reorder point is not 'reorder required'").isFalse();
        assertThat(first.get(1).belowSafetyStock()).isTrue();
        assertThat(first.get(1).usableOnHand()).isEqualTo(9);
        assertThat(first.get(2).usableOnHand()).isZero();

        List<LowStockItem> next = inventory.lowStockBatch(c, 50);
        assertThat(next).extracting(LowStockItem::sku).doesNotContain(fine);
        assertThat(inventory.lowStockBatch(start, 50)).extracting(LowStockItem::sku).doesNotContain(fine);
        // The one-at-a-time walk returns the same three.
        assertThat(inventory.lowStockBatch(start, 1)).extracting(LowStockItem::sku).containsExactly(a);
        assertThat(inventory.lowStockBatch(a, 1)).extracting(LowStockItem::sku).containsExactly(b);
        assertThat(inventory.lowStockBatch(b, 1)).extracting(LowStockItem::sku).containsExactly(c);
    }

    @Test
    void theEvaluatorAndLowStockBatchAgreeOnWhichSkusAreLow() {
        String own = fixtures.sku("LSB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), 10, null);
        fixtures.stock(own, HCM_BIN, 10, "AVAILABLE", null, null);
        assertThat(inventory.lowStockBatch(own.substring(0, own.length() - 1), 5)).extracting(LowStockItem::sku).contains(own);
        assertThat(controls.evaluate(new Sku(own)).reorderRequired()).isTrue();
        drainer.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER' AND status <> 'RESOLVED'",
                Long.class, own)).isEqualTo(1);
    }
}
