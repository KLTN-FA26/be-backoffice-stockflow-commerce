package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
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
import java.util.Map;
import java.util.UUID;

import static com.stockflow.support.AlertFixtures.HCM_BIN;
import static com.stockflow.support.AlertFixtures.HCM_BIN_2;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly expiry sweep against a real database (flow F-EXP, BR-STK-004, SCRUM-166): the boundary days (yesterday,
 * today, today+30, today+31), a lot a reservation still holds, the ledger line, the alerts per warehouse, who is told
 * (the manager of that warehouse, the planner; not another warehouse, not floor staff, never a customer), and that a
 * second run changes and says nothing.
 */
@SecuredIntegrationTest
class ExpirySweepIntegrationTest {

    @Autowired ExpirySweeper sweeper;
    @Autowired AlertDirtyDrainer drainer;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;

    private AlertFixtures fixtures;
    private UUID hcm;
    private AlertFixtures.Warehouse other;
    private LocalDate today;
    private UUID planner;
    private UUID managerHcm;
    private UUID managerOther;
    private UUID staffHcm;
    private UUID customer;

    private String yesterdaySku;
    private String heldSku;
    private String todaySku;
    private String day30Sku;
    private String day31Sku;
    private String otherSku;
    private UUID yesterdayRow;
    private UUID heldRow;
    private UUID todayRow;
    private UUID day30Row;
    private UUID day31Row;
    private UUID otherRow;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        awaitEventsDelivered();
        hcm = fixtures.hcm();
        other = fixtures.otherWarehouse();
        today = BusinessCalendar.date(Instant.now());
        planner = fixtures.staff("INVENTORY_PLANNER");
        managerHcm = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        managerOther = fixtures.staff("WAREHOUSE_MANAGER", other.id());
        staffHcm = fixtures.staff("WAREHOUSE_STAFF", hcm);
        customer = fixtures.staff("CUSTOMER");

        yesterdaySku = fixtures.sku(null, null);
        heldSku = fixtures.sku(null, null);
        todaySku = fixtures.sku(null, null);
        day30Sku = fixtures.sku(null, null);
        day31Sku = fixtures.sku(null, null);
        otherSku = fixtures.sku(null, null);
        yesterdayRow = fixtures.stock(yesterdaySku, HCM_BIN, 6, "AVAILABLE", "L-YDAY", today.minusDays(1));
        heldRow = fixtures.stock(heldSku, HCM_BIN_2, 5, "AVAILABLE", "L-HELD", today.minusDays(1));
        fixtures.hold(heldRow, 2);
        todayRow = fixtures.stock(todaySku, HCM_BIN, 4, "AVAILABLE", "L-TODAY", today);
        day30Row = fixtures.stock(day30Sku, HCM_BIN, 3, "AVAILABLE", "L-30", today.plusDays(30));
        day31Row = fixtures.stock(day31Sku, HCM_BIN, 2, "AVAILABLE", "L-31", today.plusDays(31));
        otherRow = fixtures.stock(otherSku, other.area(), 8, "AVAILABLE", "L-OTH", today.plusDays(5));
        // The inserts queued their SKUs; nothing here is a stock-level matter.
        drainer.drain();
    }

    private void awaitEventsDelivered() {
        Await.until("alert events delivered", Duration.ofSeconds(30), () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL
                  AND (event_type LIKE '%InventoryAlertsRaised' OR event_type LIKE '%SkuInventoryControlChanged')""",
                Long.class) == 0);
    }

    private String status(UUID row) {
        return jdbc.queryForObject("SELECT status FROM inventory.stock_item WHERE id = ?", String.class, row);
    }

    private List<Map<String, Object>> alerts(String sku) {
        return jdbc.queryForList("SELECT * FROM inventory.stock_alert WHERE sku = ? ORDER BY opened_at", sku);
    }

    private long ledger(UUID row) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'EXPIRY_SWEEP' AND reference_id = ?",
                Long.class, row);
    }

    private long alertRows() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert WHERE sku IN (?, ?, ?, ?, ?, ?)", Long.class,
                yesterdaySku, heldSku, todaySku, day30Sku, day31Sku, otherSku);
    }

    private long announcements() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE '%InventoryAlertsRaised'"
                + " AND (serialized_event LIKE ? OR serialized_event LIKE ? OR serialized_event LIKE ?)", Long.class,
                "%" + yesterdaySku + "%", "%" + otherSku + "%", "%" + heldSku + "%");
    }

    @Test
    void theBoundaryDaysAreBlockedAndAlertedAsTheDocsSay() {
        ExpirySweeper.Result result = sweeper.sweep();

        // Yesterday, nothing held: moved to EXPIRED with one ledger line. It is the only row the sweep blocks.
        assertThat(status(yesterdayRow)).isEqualTo("EXPIRED");
        assertThat(ledger(yesterdayRow)).isEqualTo(1);
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM inventory.stock_movement WHERE reference_type = 'EXPIRY_SWEEP'"
                + " AND reference_id = ?", yesterdayRow);
        assertThat(line).containsEntry("movement_type", "STATUS_CHANGE").containsEntry("from_status", "AVAILABLE")
                .containsEntry("to_status", "EXPIRED").containsEntry("quantity", 6).containsEntry("from_location_code", HCM_BIN)
                .containsEntry("to_location_code", HCM_BIN).containsEntry("sku", yesterdaySku).containsEntry("lot_number", "L-YDAY")
                .containsEntry("reason", "EXPIRY_SWEEP:" + today);
        assertThat(line.get("actor_id")).isNull();
        assertThat(result.blocked()).isGreaterThanOrEqualTo(1);

        // Yesterday but held by a reservation: not touched.
        assertThat(status(heldRow)).isEqualTo("AVAILABLE");
        assertThat(ledger(heldRow)).isZero();
        // Today and today+30: sellable, only warned about. Today+31: nothing.
        assertThat(status(todayRow)).isEqualTo("AVAILABLE");
        assertThat(status(day30Row)).isEqualTo("AVAILABLE");
        assertThat(status(day31Row)).isEqualTo("AVAILABLE");

        assertThat(alerts(yesterdaySku)).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("kind", "EXPIRED").containsEntry("status", "OPEN").containsEntry("warehouse_id", hcm)
                    .containsEntry("lot_number", "L-YDAY").containsEntry("observed_qty", 6).containsEntry("reservation_impaired", false);
            assertThat(a.get("expiry_date")).hasToString(today.minusDays(1).toString());
            assertThat(a.get("threshold_qty")).isNull();
        });
        assertThat(alerts(heldSku)).singleElement().satisfies(a -> assertThat(a).containsEntry("kind", "EXPIRED")
                .containsEntry("reservation_impaired", true).containsEntry("observed_qty", 5));
        assertThat(alerts(todaySku)).singleElement().satisfies(a -> assertThat(a).containsEntry("kind", "EXPIRING"));
        assertThat(alerts(day30Sku)).singleElement().satisfies(a -> assertThat(a).containsEntry("kind", "EXPIRING"));
        assertThat(alerts(day31Sku)).isEmpty();
        assertThat(alerts(otherSku)).singleElement().satisfies(a -> assertThat(a).containsEntry("kind", "EXPIRING")
                .containsEntry("warehouse_id", other.id()));
    }

    @Test
    void theManagerOfAWarehouseThePlannerAndNobodyElseAreTold() {
        sweeper.sweep();
        Await.until("planner told", Duration.ofSeconds(30), () -> fixtures.messages(planner) >= 3);
        Await.until("managers told", Duration.ofSeconds(30), () -> fixtures.messages(managerHcm) >= 2 && fixtures.messages(managerOther) >= 1);

        // HCM: an EXPIRED digest and an EXPIRING digest. The other warehouse: its own EXPIRING digest, not HCM's.
        assertThat(fixtures.messages(managerHcm, "EXPIRED")).isEqualTo(1);
        assertThat(fixtures.messages(managerHcm, "EXPIRING")).isEqualTo(1);
        assertThat(fixtures.messages(managerOther, "EXPIRING")).isEqualTo(1);
        assertThat(fixtures.messages(managerOther, "EXPIRED")).isZero();
        assertThat(fixtures.messages(managerHcm)).isEqualTo(2);
        // The planner sees every warehouse: HCM EXPIRED, HCM EXPIRING, other EXPIRING.
        assertThat(fixtures.messages(planner)).isEqualTo(3);
        // Floor staff and a customer: nothing.
        assertThat(fixtures.messages(staffHcm)).isZero();
        assertThat(fixtures.messages(customer)).isZero();

        String expiredLink = jdbc.queryForObject("SELECT link FROM notification.user_notification WHERE recipient_id = ?"
                + " AND dedupe_key LIKE 'EXPIRED:%'", String.class, managerHcm);
        assertThat(expiredLink).isEqualTo("/admin/inventory/alerts?kind=EXPIRED&warehouseId=" + hcm);
    }

    @Test
    void aSecondRunTheSameDayChangesNothingAndSaysNothing() {
        sweeper.sweep();
        awaitEventsDelivered();
        long rows = alertRows();
        long said = announcements();
        long messages = fixtures.messages(planner) + fixtures.messages(managerHcm) + fixtures.messages(managerOther);

        ExpirySweeper.Result second = sweeper.sweep();
        awaitEventsDelivered();

        assertThat(second.blocked()).isZero();
        assertThat(second.announced()).isZero();
        assertThat(alertRows()).isEqualTo(rows);
        assertThat(announcements()).isEqualTo(said);
        assertThat(ledger(yesterdayRow)).isEqualTo(1);
        assertThat(fixtures.messages(planner) + fixtures.messages(managerHcm) + fixtures.messages(managerOther)).isEqualTo(messages);
    }

    @Test
    void aHeldLotIsBlockedTheNightAfterItsReservationIsReleasedAndKeepsItsAlert() {
        sweeper.sweep();
        long episodes = alerts(heldSku).size();

        fixtures.release(heldRow);
        sweeper.sweep();

        assertThat(status(heldRow)).isEqualTo("EXPIRED");
        assertThat(ledger(heldRow)).isEqualTo(1);
        assertThat(alerts(heldSku)).hasSize((int) episodes).singleElement().satisfies(a ->
                assertThat(a).containsEntry("status", "OPEN").containsEntry("reservation_impaired", false));
    }

    @Test
    void anAlertWhoseStockIsGoneClosesAsStockGoneAndANearExpiryLotThatSellsOutToo() {
        sweeper.sweep();
        fixtures.setOnHand(todayRow, 0);          // sold out
        fixtures.setOnHand(yesterdayRow, 0);      // written off
        sweeper.sweep();

        assertThat(alerts(todaySku)).singleElement().satisfies(a -> assertThat(a).containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "STOCK_GONE"));
        assertThat(alerts(yesterdaySku)).singleElement().satisfies(a -> assertThat(a).containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "STOCK_GONE"));
        assertThat(alerts(day30Sku)).singleElement().satisfies(a -> assertThat(a).containsEntry("status", "OPEN"));
    }

    @Test
    void aLotThatIsMovedBetweenBinsOfTheSameWarehouseKeepsOneAlertWithTheSumOfItsRows() {
        sweeper.sweep();
        fixtures.stock(day30Sku, HCM_BIN_2, 4, "AVAILABLE", "L-30", today.plusDays(30));   // same lot, another bin
        sweeper.sweep();
        assertThat(alerts(day30Sku)).singleElement().satisfies(a -> assertThat(a).containsEntry("observed_qty", 7).containsEntry("status", "OPEN"));
    }

    @Test
    void expiredStockNeverCountedTowardsTheLevelSoAThresholdOnItAlertsEvenAfterTheSweepMovesTheLot() {
        // The only stock of the SKU is yesterday's lot: usable on-hand is 0 (the level reads expiry_date >= today), which is at
        // or below a reorder point of 5, and stays so after the sweep moves the lot to EXPIRED and the trigger queues the SKU.
        fixtures.setThresholds(yesterdaySku, 5, null);
        sweeper.sweep();
        drainer.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER' AND status <> 'RESOLVED'",
                Long.class, yesterdaySku)).as("0 usable, reorder point 5").isEqualTo(1);
    }
}
