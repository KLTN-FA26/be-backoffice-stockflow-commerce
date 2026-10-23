package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.service.StockOperations;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-164 against a real PostgreSQL: a count's variance asked as COUNT_VARIANCE adjustments and posted through the
 * SCRUM-145 four-eyes approval, with the commit-time carton checks (V20261020000200, rule 5 of V20261022000100) live.
 * A loose variance is split over the slot's receipt layers; a carton's is one adjustment, and its approval spreads the
 * units over the carton's rows and changes the carton once - LOST when a count found it empty, found again when a
 * count finds it. A shortage that would cut into a customer's hold is refused at approval and posts nothing.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CountVarianceAdjustmentIntegrationTest {

    private static final String BIN = "HCM-A01-1-B";
    private static final Instant EARLY = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-01-05T00:00:00Z");
    /** Cartons of this SKU are made in a unit of 50; each fixture counts a different quantity into one. */
    private static final int CARTON_FACTOR = 50;

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
    /** What a carton's row expires on; null once {@link #fifo} turns expiry tracking off. */
    private LocalDate cartonExpiry = LocalDate.now().plusYears(1);
    private UUID item;
    private UUID cartonUnit;
    private UUID clerk;
    private UUID manager;

    @BeforeEach
    void setUp() {
        sku = "CCV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "LOT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Count fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Count fixture");
            // FEFO, lot and expiry tracked: the loose split follows expiry, and a write-up is allowed (FIFO refuses
            // raising an existing layer - "FIFO replenishment requires a new receipt layer").
            jdbc.update("update inventory.inventory_items set lot_tracked=true, expiry_tracked=true,"
                    + " removal_strategy='FEFO', policy_configured=true where sku=?", sku);
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            cartonUnit = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', ?)", cartonUnit, item, CARTON_FACTOR);
        });
    }

    // ------------------------------------------------------------------ fixtures

    /** The SKU becomes FIFO, before it holds stock: expiry tracking goes with FEFO (ck_inventory_control_removal). */
    private void fifo() {
        tx.executeWithoutResult(s -> jdbc.update("update inventory.inventory_items set expiry_tracked=false,"
                + " removal_strategy='FIFO' where sku=?", sku));
        cartonExpiry = null;
    }

    private UUID loose(Instant receivedAt, LocalDate expiry, int onHand) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at, on_hand,
                    reserved, status, version, created_at) values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                id, sku, BIN, lot, expiry == null ? null : Date.valueOf(expiry), Timestamp.from(receivedAt), onHand));
        return id;
    }

    /** A carton on the bin with {@code quantity} counted into it and one row holding all of it. */
    private UUID carton(String name, int quantity, boolean unopened) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status, unopened) values (?, ?, ?, ?, ?, ?, ?, 'IN_STOCK', ?)""",
                    id, "LPN-" + sku + "-" + name, item, cartonUnit, CARTON_FACTOR, quantity, BIN, unopened);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at,
                        on_hand, reserved, status, version, created_at, stock_package_id)
                    values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), sku, BIN, lot, cartonExpiry == null ? null : Date.valueOf(cartonExpiry),
                    Timestamp.from(EARLY), quantity, id);
        });
        return id;
    }

    /** A line of its own MANUAL count, for the foreign key the adjustment's count line needs. */
    private UUID countLine(UUID carton) {
        UUID count = Identifiers.newId();
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.cycle_count (id, count_number, warehouse_id, trigger_type)
                    select ?, ?, id, 'MANUAL' from warehouse.warehouse where prefix = 'HCM'""",
                    // The random tail: a UUIDv7's head is its millisecond clock, the same for every count of a run.
                    count, "CC-IT-" + count.toString().substring(24).toUpperCase(Locale.ROOT));
            jdbc.update("""
                    insert into inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number,
                        expected_qty, stock_package_id) values (?, ?, ?, ?, ?, 0, ?)""",
                    line, count, BIN, sku, lot, carton);
        });
        return line;
    }

    private CountSlot looseSlot() {
        return CountSlot.loose(new LocationId(BIN), new Sku(sku), lot);
    }

    private CountSlot cartonSlot(UUID carton) {
        return CountSlot.carton(new LocationId(BIN), new Sku(sku), lot, new StockPackageId(carton));
    }

    private Map<String, Object> cartonState(UUID id) {
        return jdbc.queryForMap("""
                select p.status, p.unopened,
                       (select coalesce(sum(s.on_hand), 0) from inventory.stock_item s where s.stock_package_id = p.id) as held
                  from inventory.stock_package p where p.id = ?""", id);
    }

    private int onHand(UUID row) {
        return jdbc.queryForObject("select on_hand from inventory.stock_item where id = ?", Integer.class, row);
    }

    private List<Map<String, Object>> adjustmentLedger() {
        return jdbc.queryForList("""
                select reference_id, quantity, from_location_code, to_location_code, from_stock_package_id,
                       to_stock_package_id
                  from inventory.stock_movement where sku = ? and movement_type = 'ADJUSTMENT'
                 order by occurred_at, quantity""", sku);
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991226-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /** A customer's hold of {@code quantity}, allocated onto the SKU's one stock row (as in the pick tests). */
    private UUID hold(UUID order, int quantity) {
        return tx.execute(s -> {
            UUID id = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order))
                    .reservationIds().getFirst();
            inventory.pinReservations(order);
            return inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(order, "HCM")).allocations()
                    .stream().filter(allocation -> allocation.reservationId().equals(id)).findFirst().orElseThrow()
                    .holdIds().getFirst();
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a typed-in request cannot claim a count variance: VALIDATION_FAILED, nothing written")
    void manualAdjustmentCannotClaimACountVariance() {
        loose(EARLY, LocalDate.now().plusYears(1), 10);

        assertThatThrownBy(() -> operations.requestAdjustment(new RequestAdjustmentCommand(BIN, new Sku(sku), lot, -1,
                StockAdjustmentReason.COUNT_VARIANCE, "x", clerk)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where sku = ?", Integer.class,
                sku)).isZero();
    }

    @Test
    @DisplayName("a loose shortage is split in FEFO order, one adjustment per layer, and posts on approval")
    void looseShortageSplitsFefoAndPostsOnApproval() {
        UUID soon = loose(LATE, LocalDate.now().plusMonths(6), 2);
        UUID later = loose(EARLY, LocalDate.now().plusYears(1), 10);
        UUID line = countLine(null);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, looseSlot(), -5, clerk, "CC-1");

        assertThat(asked).extracting(StockAdjustmentSummary::quantityDelta).containsExactly(-2, -3);
        assertThat(asked).allSatisfy(a -> {
            assertThat(a.reason()).isEqualTo(StockAdjustmentReason.COUNT_VARIANCE);
            assertThat(a.cycleCountLineId()).isEqualTo(line);
            assertThat(a.stockPackageId()).isNull();
            assertThat(a.status()).isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
        });
        assertThat(onHand(soon)).as("nothing posted before approval").isEqualTo(2);

        asked.forEach(a -> operations.approve(a.adjustmentId(), manager));

        assertThat(onHand(soon)).isZero();
        assertThat(onHand(later)).isEqualTo(7);
        assertThat(adjustmentLedger()).extracting(row -> row.get("reference_id"))
                .containsExactlyInAnyOrder(asked.get(0).adjustmentId(), asked.get(1).adjustmentId());
    }

    @Test
    @DisplayName("a loose shortage takes free units first: a FEFO-first layer held whole for a customer is left alone")
    void looseShortageSparesAFullyHeldLayer() {
        UUID soon = loose(LATE, LocalDate.now().plusMonths(6), 3);
        UUID later = loose(EARLY, LocalDate.now().plusYears(1), 10);
        UUID hold = hold(confirmedOrder(), 3);
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where id = ?", Integer.class, soon))
                .as("FEFO allocated the whole of the earliest-expiring layer").isEqualTo(3);
        UUID line = countLine(null);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, looseSlot(), -2, clerk, "CC-1");

        assertThat(asked).as("one adjustment, on the layer with free units").singleElement()
                .satisfies(a -> assertThat(a.quantityDelta()).isEqualTo(-2));
        operations.approve(asked.getFirst().adjustmentId(), manager);

        assertThat(onHand(soon)).as("the held layer is untouched").isEqualTo(3);
        assertThat(onHand(later)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select status || ':' || quantity from inventory.stock_reservation where id = ?",
                String.class, hold)).endsWith(":3");
    }

    @Test
    @DisplayName("a loose surplus lands on the newest layer")
    void looseSurplusOnTheNewestLayer() {
        UUID older = loose(EARLY, LocalDate.now().plusMonths(6), 4);
        UUID newer = loose(LATE, LocalDate.now().plusYears(1), 6);
        UUID line = countLine(null);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, looseSlot(), 3, clerk, null);

        assertThat(asked).singleElement().satisfies(a -> assertThat(a.quantityDelta()).isEqualTo(3));
        operations.approve(asked.getFirst().adjustmentId(), manager);

        assertThat(onHand(older)).isEqualTo(4);
        assertThat(onHand(newer)).isEqualTo(9);
        assertThat(adjustmentLedger()).singleElement().satisfies(row -> {
            assertThat(row.get("to_location_code")).isEqualTo(BIN);
            assertThat(row.get("from_location_code")).isNull();
        });
    }

    @Test
    @DisplayName("FIFO: a loose surplus opens a new layer received at approval; the counted layer is not raised")
    void fifoLooseSurplusOpensANewLayer() {
        fifo();
        UUID old = loose(EARLY, null, 4);
        UUID line = countLine(null);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, looseSlot(), 3, clerk, null);
        assertThat(asked).singleElement().satisfies(a -> assertThat(a.quantityDelta()).isEqualTo(3));
        operations.approve(asked.getFirst().adjustmentId(), manager);

        assertThat(onHand(old)).isEqualTo(4);
        assertThat(jdbc.queryForList("""
                select on_hand, received_at, lot_number, location_code, stock_package_id from inventory.stock_item
                 where sku = ? and id <> ?""", sku, old)).singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("on_hand", 3).containsEntry("lot_number", lot)
                            .containsEntry("location_code", BIN);
                    assertThat(row.get("stock_package_id")).isNull();
                    assertThat(((Timestamp) row.get("received_at")).toInstant()).isAfter(EARLY);
                });
        assertThat(adjustmentLedger()).singleElement().satisfies(row -> {
            assertThat(row.get("to_location_code")).isEqualTo(BIN);
            assertThat(row.get("quantity")).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("FIFO: a LOST carton found again is refilled on its own row, not a new layer")
    void fifoCartonFoundAgainRefillsItsOwnRow() {
        fifo();
        UUID box = carton("R", 20, true);
        operations.approve(operations.requestCountVariance(countLine(box), cartonSlot(box), -20, clerk, null)
                .getFirst().adjustmentId(), manager);
        assertThat(cartonState(box)).containsEntry("status", "LOST");

        operations.approve(operations.requestCountVariance(countLine(box), cartonSlot(box), 20, clerk, null)
                .getFirst().adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", 20L);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ?", Integer.class, sku))
                .as("the carton's one row, no new layer").isEqualTo(1);
    }

    @Test
    @DisplayName("an unopened carton a count did not find is written down to nothing and goes LOST")
    void cartonNotFoundGoesLost() {
        UUID box = carton("L", 50, true);
        UUID line = countLine(box);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, cartonSlot(box), -50, clerk, null);

        assertThat(asked).singleElement().satisfies(a -> {
            assertThat(a.stockPackageId()).isEqualTo(box);
            assertThat(a.quantityDelta()).isEqualTo(-50);
        });
        operations.approve(asked.getFirst().adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "LOST").containsEntry("held", 0L);
        assertThat(adjustmentLedger()).singleElement().satisfies(row -> {
            assertThat(row.get("from_stock_package_id")).isEqualTo(box);
            assertThat(row.get("from_location_code")).isEqualTo(BIN);
            assertThat(row.get("quantity")).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("an opened carton written down keeps the rest and stays in stock, open")
    void openedCartonWrittenDownStaysOpen() {
        UUID box = carton("O", 30, false);
        UUID line = countLine(box);

        List<StockAdjustmentSummary> asked = operations.requestCountVariance(line, cartonSlot(box), -4, clerk, null);
        operations.approve(asked.getFirst().adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("unopened", false)
                .containsEntry("held", 26L);
    }

    @Test
    @DisplayName("a LOST carton a later count finds is found again, its units back on its row")
    void lostCartonFoundAgain() {
        UUID box = carton("F", 20, true);
        operations.approve(operations.requestCountVariance(countLine(box), cartonSlot(box), -20, clerk, null)
                .getFirst().adjustmentId(), manager);
        assertThat(cartonState(box)).containsEntry("status", "LOST");

        List<StockAdjustmentSummary> found = operations.requestCountVariance(countLine(box), cartonSlot(box), 20, clerk,
                null);
        operations.approve(found.getFirst().adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", 20L);
        assertThat(jdbc.queryForObject("select closed_at is null from inventory.stock_package where id = ?",
                Boolean.class, box)).isTrue();
        assertThat(adjustmentLedger()).filteredOn(row -> found.getFirst().adjustmentId().equals(row.get("reference_id")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("to_stock_package_id")).isEqualTo(box);
                    assertThat(row.get("to_location_code")).isEqualTo(BIN);
                    assertThat(row.get("from_stock_package_id")).isNull();
                });
    }

    @Test
    @DisplayName("a shortage that would cut into a customer's hold is refused at approval; nothing posts, the hold stays")
    void shortageIntoAHoldIsRefusedAtApproval() {
        UUID box = carton("H", 10, true);
        UUID hold = hold(confirmedOrder(), 8);
        String holdBefore = jdbc.queryForObject("select status || ':' || quantity from inventory.stock_reservation"
                + " where id = ?", String.class, hold);
        UUID line = countLine(box);
        StockAdjustmentSummary asked = operations.requestCountVariance(line, cartonSlot(box), -5, clerk, null)
                .getFirst();

        assertThatThrownBy(() -> operations.approve(asked.adjustmentId(), manager))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("unopened", true)
                .containsEntry("held", 10L);
        assertThat(adjustmentLedger()).isEmpty();
        assertThat(jdbc.queryForObject("select status from inventory.stock_adjustment where id = ?", String.class,
                asked.adjustmentId())).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select status || ':' || quantity from inventory.stock_reservation where id = ?",
                String.class, hold)).isEqualTo(holdBefore);
    }

    @Test
    @DisplayName("the person who asked for a count variance cannot approve it")
    void requesterCannotApprove() {
        UUID box = carton("S", 30, false);
        StockAdjustmentSummary asked = operations.requestCountVariance(countLine(box), cartonSlot(box), -4, clerk,
                null).getFirst();

        assertThatThrownBy(() -> operations.approve(asked.adjustmentId(), clerk))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        assertThat(cartonState(box)).containsEntry("held", 30L);
        assertThat(adjustmentLedger()).isEmpty();
    }
}
