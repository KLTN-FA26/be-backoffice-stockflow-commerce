package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.DemoData;
import com.stockflow.support.Holds;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.inventory.internal.domain.AdjustmentDirection.RESTOCK;
import static com.stockflow.inventory.internal.domain.AdjustmentDirection.WRITE_OFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-164 against a real PostgreSQL, by carton (team plan 4C-3; kltn-docs 20 §4.2 c8, BR-09): a count's variance is
 * asked as one whole-carton COUNT_VARIANCE adjustment and posted through the SCRUM-145 four-eyes approval, with the
 * commit-time carton checks (V20261020000200, V20261113000300) live. A carton a count did not find is written off
 * whole and goes LOST; a LOST carton a later count finds is restocked whole on its own row, its lot kept. A
 * variance says which way and no quantity (V20261231000100), so part of a carton cannot even be asked; a carton held
 * for a customer's order, counted missing, is written off with its hold given up as a short pick (any other
 * adjustment of it is refused).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CountVarianceAdjustmentIntegrationTest {

    private static final String BIN = "HCM-A01-1-B";
    private static final Instant EARLY = Instant.parse("2026-01-02T00:00:00Z");
    /** Cartons of this SKU are made in a unit of 50. */
    private static final int CARTON_FACTOR = 50;

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
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
            // Every SKU is lot-tracked and first in, first out since 5-5 (kltn-docs 01 BR-17): there is no policy to set.
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            cartonUnit = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, variant_id, unit_of_measure, factor)"
                    + " values (?, (select variant_id from inventory.inventory_items where id = ?), 'CARTON', ?)", cartonUnit, item, CARTON_FACTOR);
        });
    }

    // ------------------------------------------------------------------ fixtures

    /** A whole carton on the bin: AVAILABLE, of the test's lot. */
    private UUID carton(String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        counted_at, location_code, status, stock_status, lot_id, received_at)
                    values (?, ?, ?, ?, ?, now(), ?, 'IN_STOCK', 'AVAILABLE', ?, ?)""",
                    id, "LPN-" + sku + "-" + name, item, cartonUnit, CARTON_FACTOR, BIN,
                    com.stockflow.support.CartonRows.lot(jdbc, sku, lot, EARLY), Timestamp.from(EARLY));
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
                        booked, stock_package_id) values (?, ?, ?, ?, ?, false, ?)""",
                    line, count, BIN, sku, lot, carton);
        });
        return line;
    }

    private CountSlot cartonSlot(UUID carton) {
        return CountSlot.carton(new LocationId(BIN), new Sku(sku), lot, new StockPackageId(carton));
    }

    private Map<String, Object> cartonState(UUID id) {
        return jdbc.queryForMap("""
                select p.status,
                       (case when p.status = 'IN_STOCK' then p.nominal_qty else 0 end)::bigint as held
                  from inventory.stock_package p where p.id = ?""", id);
    }

    private List<Map<String, Object>> adjustmentLedger() {
        return jdbc.queryForList("""
                select m.reference_id, p.nominal_qty as quantity, m.from_location_code, m.to_location_code,
                       m.stock_package_id
                  from inventory.stock_movement m join inventory.stock_package p on p.id = m.stock_package_id
                 where m.sku = ? and m.movement_type = 'ADJUSTMENT'
                 order by m.occurred_at, m.id""", sku);
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order,
                "SO-20991226-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /** A customer's hold of one whole carton, allocated onto the SKU's one carton (as in the pick tests). */
    private UUID hold(UUID order) {
        return tx.execute(s -> {
            UUID id = Holds.units(inventory, order, sku, "HCM", CARTON_FACTOR);
            return inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(order, "HCM")).allocations()
                    .stream().filter(allocation -> allocation.reservationId().equals(id)).findFirst().orElseThrow()
                    .holdIds().getFirst();
        });
    }

    private static ErrorCode codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).errorCode();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a typed-in request cannot claim a count variance: VALIDATION_FAILED, nothing written")
    void manualAdjustmentCannotClaimACountVariance() {
        UUID box = carton("A");

        assertThat(codeOf(catchThrowable(() -> operations.requestPackageAdjustment(
                new StockOperations.PackageAdjustmentRequest("LPN-" + sku + "-A", WRITE_OFF,
                        StockAdjustmentReason.COUNT_VARIANCE, "x", clerk))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(codeOf(catchThrowable(() -> operations.requestAdjustment(new RequestAdjustmentCommand(null,
                BIN, new Sku(sku), StockAdjustmentReason.COUNT_VARIANCE, "x", clerk)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where sku = ?", Integer.class,
                sku)).isZero();
        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK");
    }

    @Test
    @DisplayName("a carton a count did not find is one whole-carton shortage: approved, it is written off and goes LOST")
    void cartonNotFoundGoesLost() {
        UUID box = carton("L");
        UUID line = countLine(box);

        StockAdjustmentSummary asked = operations.requestCountVariance(line, cartonSlot(box), WRITE_OFF, clerk,
                "CC-1");

        assertThat(asked.stockPackageId()).isEqualTo(box);
        assertThat(asked.direction()).isEqualTo(com.stockflow.inventory.api.StockAdjustmentDirection.WRITE_OFF);
        assertThat(asked.reason()).isEqualTo(StockAdjustmentReason.COUNT_VARIANCE);
        assertThat(asked.cycleCountLineId()).isEqualTo(line);
        assertThat(asked.status()).isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
        assertThat(cartonState(box)).as("nothing posted before approval").containsEntry("status", "IN_STOCK")
                .containsEntry("held", (long) CARTON_FACTOR);

        operations.approve(asked.adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "LOST").containsEntry("held", 0L);
        assertThat(jdbc.queryForObject("select closed_at is not null from inventory.stock_package where id = ?",
                Boolean.class, box)).isTrue();
        assertThat(adjustmentLedger()).singleElement().satisfies(row -> {
            assertThat(row.get("stock_package_id")).isEqualTo(box);
            assertThat(row.get("from_location_code")).isEqualTo(BIN);
            assertThat(row.get("quantity")).isEqualTo(CARTON_FACTOR);
        });
    }

    @Test
    @DisplayName("WF-05 section 8: the variance keeps who counted its line, the counter is refused as its decider - by "
            + "the aggregate and by the table - and the posted ledger line names requester, counter and approver")
    void theCounterIsKeptRefusedAndNamedOnTheLedger() {
        UUID box = carton("C");
        UUID line = countLine(box);
        UUID counter = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("update inventory.cycle_count_line set found = false, "
                + "counted_by = ?, counted_at = now() where id = ?", counter, line));

        StockAdjustmentSummary asked = operations.requestCountVariance(line, cartonSlot(box), WRITE_OFF, clerk,
                "CC-8");

        assertThat(asked.countedBy()).as("kept on the adjustment, not looked up when it is decided").isEqualTo(counter);
        assertThat(jdbc.queryForObject("select counted_by from inventory.stock_adjustment where id = ?", UUID.class,
                asked.adjustmentId())).isEqualTo(counter);

        assertThatThrownBy(() -> operations.approve(asked.adjustmentId(), counter))
                .extracting(CountVarianceAdjustmentIntegrationTest::codeOf).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        assertThatThrownBy(() -> operations.reject(asked.adjustmentId(), counter, "no"))
                .extracting(CountVarianceAdjustmentIntegrationTest::codeOf).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        // The table says it again, whoever writes the decision.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("update inventory.stock_adjustment set "
                + "status = 'REJECTED', decided_by = ?, decided_at = now(), rejection_reason = 'x' where id = ?",
                counter, asked.adjustmentId())))
                .hasMessageContaining("ck_stock_adjustment_counter_not_decider");

        operations.approve(asked.adjustmentId(), manager);

        assertThat(jdbc.queryForMap("select requested_by, counted_by, actor_id from inventory.stock_movement "
                + "where reference_type = 'STOCK_ADJUSTMENT' and reference_id = ?", asked.adjustmentId()))
                .containsEntry("requested_by", clerk).containsEntry("counted_by", counter)
                .containsEntry("actor_id", manager);
        // A ledger line of an adjustment that names other people than the adjustment does is refused.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code,
                    from_status, reference_type, reference_id, actor_id, occurred_at, stock_package_id,
                    requested_by)
                values (?, 'ADJUSTMENT', ?, ?, ?, 'AVAILABLE', 'STOCK_ADJUSTMENT', ?, ?, now(), ?, ?)""",
                Identifiers.newId(), sku, lot, BIN, asked.adjustmentId(), manager, box, manager)))
                .hasMessageContaining("names who requested and who counted");
    }

    @Test
    @DisplayName("a LOST carton a later count finds is restocked whole on its own row: IN_STOCK, lot kept, no new layer")
    void lostCartonFoundAgain() {
        UUID box = carton("F");
        operations.approve(operations.requestCountVariance(countLine(box), cartonSlot(box), WRITE_OFF, clerk, null)
                .adjustmentId(), manager);
        assertThat(cartonState(box)).containsEntry("status", "LOST");

        StockAdjustmentSummary found = operations.requestCountVariance(countLine(box), cartonSlot(box), RESTOCK,
                clerk, null);
        operations.approve(found.adjustmentId(), manager);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", (long) CARTON_FACTOR);
        assertThat(jdbc.queryForObject("select closed_at is null from inventory.stock_package where id = ?",
                Boolean.class, box)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_package where inventory_item_id = ?",
                Integer.class, item)).as("the very carton, no second one").isEqualTo(1);
        assertThat(jdbc.queryForObject("select l.lot_number from inventory.stock_package p join inventory.stock_lot l"
                + " on l.id = p.lot_id where p.id = ?", String.class, box)).as("BR-18: its own lot").isEqualTo(lot);
        assertThat(adjustmentLedger()).filteredOn(row -> found.adjustmentId().equals(row.get("reference_id")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("stock_package_id")).isEqualTo(box);
                    assertThat(row.get("to_location_code")).isEqualTo(BIN);
                    assertThat(row.get("from_location_code")).as("back into stock: no place it left").isNull();
                });
    }

    @Test
    @DisplayName("a count variance of no carton is a caller's bug: refused before anything is written")
    void aVarianceOfNoCartonIsRefused() {
        CountSlot loose = new CountSlot(new LocationId(BIN), new Sku(sku), lot, null);

        assertThatThrownBy(() -> operations.requestCountVariance(UUID.randomUUID(), loose, WRITE_OFF, clerk,
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where sku = ?", Integer.class,
                sku)).isZero();
    }

    @Test
    @DisplayName("a carton held for a customer's order, counted missing: approved, it is written off and the hold is "
            + "given up as a short pick (20 §4.2 step 8); the shortage itself is fulfillment's, after the commit")
    void shortageOfAHeldCartonGivesUpTheHold() {
        UUID box = carton("H");
        UUID hold = hold(confirmedOrder());
        UUID line = countLine(box);
        StockAdjustmentSummary asked = operations.requestCountVariance(line, cartonSlot(box), WRITE_OFF, clerk,
                null);

        StockAdjustmentSummary posted = operations.approve(asked.adjustmentId(), manager);

        assertThat(posted.status()).isEqualTo(StockAdjustmentStatus.POSTED);
        assertThat(cartonState(box)).containsEntry("status", "LOST").containsEntry("held", 0L);
        assertThat(adjustmentLedger()).singleElement().satisfies(row -> {
            assertThat(row.get("stock_package_id")).isEqualTo(box);
            assertThat(row.get("quantity")).isEqualTo(CARTON_FACTOR);
        });
        assertThat(jdbc.queryForObject("select status || ':' || release_reason from inventory.stock_reservation "
                + "where id = ?", String.class, hold)).isEqualTo("RELEASED:SHORT_PICKED");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_reservation where stock_package_id = ?"
                + " and status = 'HELD'", Integer.class, box)).as("no live allocation names the carton").isZero();
    }

    @Test
    @DisplayName("any other adjustment of a carton held for an order is still refused (STOCK_PACKAGE_HELD): nothing "
            + "posts, the hold stays")
    void otherAdjustmentOfAHeldCartonIsStillRefused() {
        UUID box = carton("I");
        UUID hold = hold(confirmedOrder());
        String holdBefore = jdbc.queryForObject("select status || ':' || cartons || 'x' || units_per_carton from inventory.stock_reservation"
                + " where id = ?", String.class, hold);
        // Asking to write a held carton off is refused at once, and a held carton cannot be asked for once pending:
        // the request is written as a row, as one asked before the hold would have left it.
        UUID adjustment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number,
                    direction, reason_code, status, requested_by, stock_package_id)
                values (?, ?, ?, ?, ?, 'WRITE_OFF', 'DAMAGED', 'PENDING_APPROVAL', ?, ?)""", adjustment,
                "ADJ-IT-" + adjustment.toString().substring(24).toUpperCase(Locale.ROOT), BIN, sku, lot,
                clerk, box));

        Throwable refused = catchThrowable(() -> operations.approve(adjustment, manager));

        assertThat(codeOf(refused)).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", (long) CARTON_FACTOR);
        assertThat(adjustmentLedger()).isEmpty();
        assertThat(jdbc.queryForObject("select status || ':' || cartons || 'x' || units_per_carton from inventory.stock_reservation where id = ?",
                String.class, hold)).isEqualTo(holdBefore);
    }

    @Test
    @DisplayName("the person who asked for a count variance cannot approve it")
    void requesterCannotApprove() {
        UUID box = carton("S");
        StockAdjustmentSummary asked = operations.requestCountVariance(countLine(box), cartonSlot(box),
                WRITE_OFF, clerk, null);

        assertThatThrownBy(() -> operations.approve(asked.adjustmentId(), clerk))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        assertThat(cartonState(box)).containsEntry("held", (long) CARTON_FACTOR);
        assertThat(adjustmentLedger()).isEmpty();
    }

    @Test
    @DisplayName("a rejected variance changes nothing: the carton stays in stock, and another adjustment can be asked")
    void rejectedVarianceChangesNothing() {
        UUID box = carton("J");
        StockAdjustmentSummary asked = operations.requestCountVariance(countLine(box), cartonSlot(box),
                WRITE_OFF, clerk, null);

        operations.reject(asked.adjustmentId(), manager, "Counted the wrong bin");

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", (long) CARTON_FACTOR);
        assertThat(adjustmentLedger()).isEmpty();
        // the carton is free of a pending adjustment again (uk_stock_adjustment_pending_package)
        operations.requestCountVariance(countLine(box), cartonSlot(box), WRITE_OFF, clerk, null);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_adjustment where stock_package_id = ?",
                Integer.class, box)).isEqualTo(2);
    }
}
