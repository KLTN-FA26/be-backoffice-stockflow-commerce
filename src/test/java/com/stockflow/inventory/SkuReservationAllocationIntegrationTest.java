package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReallocateStockCommand;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-152 against a real database: checkout reserves quantity of a SKU on no location; releasing the order to a
 * warehouse allocates it to pickable bins of that warehouse FIFO, by the earliest real receipt time (kltn-docs 01
 * BR-17, 07 BR-03), never on a non-pickable area or another warehouse; the SKU never promises more
 * than it has, under concurrency too, and the database refuses what the application would not.
 *
 * <p>Each test owns a fresh SKU with this stock (today = the business date), all of it in sealed cartons of one unit
 * each, so an allocation takes one whole carton per unit (team plan 4C-2: whole cartons only):</p>
 * <pre>
 *   HCM-A01-1-B  bin   5  received 2025-09-01   (oldest receipt)
 *   HCM-A01-1-A  bin   5  received 2025-10-01
 *   HCM-A02-2-A  bin   2  received 2025-11-01
 *   HCM-A01-2-A  bin   5  received 2025-12-01
 *   HCM-RCV01    area  5  received 2026-03-01   not pickable: never allocated, so never promised either
 * </pre>
 * Available to promise is therefore 17: only what a release could allocate is promised (review of 4C-2).
 */
@IntegrationTest
@Import({PostgresContainer.class, com.stockflow.support.RecordedEvents.class})
class SkuReservationAllocationIntegrationTest {

    @Autowired InventoryService inventory;
    @Autowired com.stockflow.support.RecordedEvents.Recorder recorded;
    @Autowired com.stockflow.inventory.consumption.PickConsumption consumption;
    @Autowired com.stockflow.inventory.internal.service.CycleCounts cycleCounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private String sku;
    /** The stock rows (one per carton) at each location. */
    private final Map<String, List<UUID>> rows = new java.util.HashMap<>();
    private LocalDate today;

    @BeforeEach
    void stock() {
        sku = "ALLOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Allocation fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Allocation fixture','')", Identifiers.newId(), product, sku);
            row("HCM-A01-1-A", 5, "2025-10-01T00:00:00Z");
            row("HCM-A01-1-B", 5, "2025-09-01T00:00:00Z");
            row("HCM-A01-2-A", 5, "2025-12-01T00:00:00Z");
            row("HCM-A02-2-A", 2, "2025-11-01T00:00:00Z");
            row("HCM-RCV01", 5, "2026-03-01T00:00:00Z");
        });
    }

    /** {@code onHand} sealed cartons of one unit at {@code location}. */
    private void row(String location, int onHand, String receivedAt) {
        rows.put(location, com.stockflow.support.CartonRows.stock(jdbc, sku, location, onHand, 1, null, Instant.parse(receivedAt), "AVAILABLE").stream()
                .map(com.stockflow.support.CartonRows.Carton::stockItemId).toList());
    }

    private static List<String> times(int count, String location) {
        return java.util.Collections.nCopies(count, location);
    }

    private static List<String> concat(List<String> a, List<String> b, List<String> c) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    /** The carton the allocation holds. */
    private UUID cartonOf(StockReservation allocation) {
        return jdbc.queryForObject("SELECT stock_package_id FROM inventory.stock_item WHERE id = ?", UUID.class,
                allocation.stockItemId());
    }

    private UUID order() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""",
                order, "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /** A SKU hold Sales took for the order at HCM (4B-7): its reservation id. */
    private record Hold(UUID reservationId) {
    }

    private Hold reserve(UUID order, int quantity) {
        return reserve(order, UUID.randomUUID(), quantity);
    }

    /** {@code quantity} units held at HCM for the order; fails the test when fewer could be. */
    private Hold reserve(UUID order, UUID request, int quantity) {
        HoldStockResult held = hold(order, request, quantity);
        assertThat(held.cartonsHeld()).as("%d of %s held", quantity, sku).isOne();
        return new Hold(held.reservationIds().getFirst());
    }

    /**
     * The hold as asked: {@code quantity} units as one block, or nothing when HCM has fewer to promise - a shortage
     * holds nothing (4B-7: held later, when stock arrives), it is not refused.
     */
    private HoldStockResult hold(UUID order, UUID request, int quantity) {
        return tx.execute(s -> {
            inventory.prepareReservation(Set.of(new Sku(sku)));
            return inventory.holdAtWarehouse(new HoldStockCommand(request, order, new Sku(sku), "HCM", 1, quantity));
        });
    }

    private AllocateStockResult allocate(UUID order, String warehouse) {
        return tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, warehouse)));
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    private int reservedOn(String location) {
        return jdbc.queryForObject("SELECT sum(reserved) FROM inventory.stock_item WHERE sku = ? AND location_code = ?",
                Integer.class, sku, location);
    }

    private int reservedEverywhere() {
        return jdbc.queryForObject("SELECT sum(reserved) FROM inventory.stock_item WHERE sku = ?", Integer.class, sku);
    }

    private String status(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM inventory.stock_reservation WHERE id = ?", String.class,
                reservation);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a hold takes the SKU at a warehouse on no location: ATP falls, no stock row is held, no deadline, "
            + "a replay is the same hold")
    void aHoldReservesTheSkuOnly() {
        assertThat(atp()).isEqualTo(17);
        UUID order = order();
        UUID request = UUID.randomUUID();
        Hold held = reserve(order, request, 7);

        assertThat(atp()).isEqualTo(10);
        assertThat(reservedEverywhere()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT stock_item_id, sku, status, quantity, warehouse_code, expires_at IS NOT NULL AS deadline
                  FROM inventory.stock_reservation WHERE id = ?""", held.reservationId()))
                .containsEntry("stock_item_id", null).containsEntry("sku", sku).containsEntry("status", "HELD")
                .containsEntry("quantity", 7).containsEntry("warehouse_code", "HCM").containsEntry("deadline", false);

        assertThat(reserve(order, request, 7).reservationId()).isEqualTo(held.reservationId());
        assertThat(atp()).isEqualTo(10);
        assertThat(inventory.holdsOf(order)).singleElement()
                .satisfies(hold -> assertThat(hold.locationCode()).isNull());
        assertThat(inventory.holds(List.of(held.reservationId()))).singleElement()
                .satisfies(view -> assertThat(view.status()).isEqualTo(HoldView.Status.HELD));
        assertThat(hold(order(), UUID.randomUUID(), 16).cartonsHeld()).as("10 left to promise: nothing held")
                .isZero();
        assertThat(atp()).isEqualTo(10);
    }

    @Test
    @DisplayName("release allocates whole cartons FIFO on pickable bins: the oldest receipt first; never an area")
    void allocationFollowsFifoOnPickableBins() {
        UUID order = order();
        Hold soft = reserve(order, 12);
        int atpBefore = atp();

        AllocateStockResult result = allocate(order, "hcm");

        assertThat(result.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.reservationId()).isEqualTo(soft.reservationId());
            // One hold per carton, of one unit each.
            assertThat(allocation.holds()).extracting(StockReservation::locationCode).containsExactlyElementsOf(
                    concat(times(5, "HCM-A01-1-B"), times(5, "HCM-A01-1-A"), times(2, "HCM-A02-2-A")));
            assertThat(allocation.holds()).extracting(StockReservation::quantity).containsOnly(1);
        });
        assertThat(reservedOn("HCM-A01-1-B")).isEqualTo(5);
        assertThat(reservedOn("HCM-A01-1-A")).isEqualTo(5);
        assertThat(reservedOn("HCM-A02-2-A")).isEqualTo(2);
        assertThat(reservedOn("HCM-A01-2-A")).isZero();
        assertThat(reservedOn("HCM-RCV01")).as("not pickable").isZero();
        assertThat(status(soft.reservationId())).isEqualTo("ALLOCATED");
        assertThat(atp()).as("an allocation moves units from reserved to held, it does not promise more").isEqualTo(atpBefore);
        assertThat(jdbc.queryForList("""
                SELECT allocated_from_id::text || ':' || (expires_at IS NULL) FROM inventory.stock_reservation
                 WHERE allocated_from_id IS NOT NULL AND order_id = ?""", String.class, order))
                .hasSize(12).containsOnly(soft.reservationId() + ":true");

        assertThat(recorded.matching(com.stockflow.contracts.StockAllocated.class, e -> e.orderId().equals(order)))
                .as("one StockAllocated per carton")
                .extracting(com.stockflow.contracts.StockAllocated::locationCode)
                .containsExactlyInAnyOrderElementsOf(
                        concat(times(5, "HCM-A01-1-B"), times(5, "HCM-A01-1-A"), times(2, "HCM-A02-2-A")));
        assertThat(allocate(order, "HCM").allocations()).as("allocating again changes nothing").isEmpty();
        assertThat(reservedEverywhere()).isEqualTo(12);
        // SCRUM-476: Inventory Level shows the 12 allocated units apart from the other holds; ATP is what is left to
        // allocate on pickable bins, 17 - 12 (review C4 P-1: HCM-RCV01's 5 are on hand, never promised).
        assertThat(inventory.levelsOf(new Sku(sku)))
                .containsExactly(new com.stockflow.inventory.api.StockLevel(sku, "HCM", 22, 22, 0, 12, 5));
    }

    @Test
    @DisplayName("review C4 P-1: stock in receiving and staging areas is on hand but promised by no warehouse either")
    void areaStockIsNotPromisedAtItsWarehouse() {
        tx.executeWithoutResult(s -> row("HCM-STG01", 3, "2025-08-01T00:00:00Z"));

        assertThat(atp()).as("system-wide: the 17 cartons on pickable bins").isEqualTo(17);
        assertThat(inventory.availableToPromiseByWarehouse(new Sku(sku))).as("HCM promises the same 17, not the 5 in "
                + "HCM-RCV01 nor the 3 in HCM-STG01").containsEntry("HCM", 17);
        assertThat(hold(order(), UUID.randomUUID(), 18).cartonsHeld()).as("18 is more than HCM promises").isZero();
        reserve(order(), 17);
        assertThat(inventory.availableToPromiseByWarehouse(new Sku(sku))).containsEntry("HCM", 0);
        assertThat(atp()).isZero();
    }

    @Test
    @DisplayName("a warehouse that cannot cover the order allocates nothing and leaves the reservation held")
    void allocationIsAllOrNothing() {
        UUID order = order();
        Hold soft = reserve(order, 13);   // 17 pickable in HCM: enough
        UUID other = order();
        Hold rest = reserve(other, 4);    // ATP 17 - 13 = 4: the area's cartons are not promised
        assertThat(hold(order(), UUID.randomUUID(), 1).cartonsHeld()).as("nothing left to promise").isZero();

        assertThat(allocate(order, "HCM").allocations()).hasSize(1);
        assertThat(code(catchThrowable(() -> allocate(other, "ZZZ")))).as("held at HCM: allocated there or not at all")
                .isEqualTo(ErrorCode.RESERVATION_WAREHOUSE_MISMATCH);
        assertThat(status(rest.reservationId())).isEqualTo("HELD");
        assertThat(reservedEverywhere()).isEqualTo(13);
        assertThat(status(soft.reservationId())).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("concurrent holds never promise more than the SKU has")
    void noOversellUnderConcurrency() throws Exception {
        int threads = 8;
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            orders.add(order());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID order : orders) {
                Callable<Boolean> checkout = () -> {
                    start.await();
                    return hold(order, UUID.randomUUID(), 4).cartonsHeld() == 1;
                };
                results.add(pool.submit(checkout));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                succeeded += result.get() ? 1 : 0;
            }
            assertThat(succeeded).isEqualTo(4);   // 4 x 4 = 16 <= 17 < 20
        } finally {
            pool.shutdownNow();
        }
        assertThat(atp()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT sum(quantity) FROM inventory.stock_reservation
                 WHERE sku = ? AND stock_item_id IS NULL AND status = 'HELD'""", Integer.class, sku)).isEqualTo(16);
    }

    @Test
    @DisplayName("the database refuses a SKU reservation no stock covers, whatever wrote it")
    void databaseSafetyNet() {
        UUID order = order();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity,
                                                         reserved_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 100, NOW(), NOW() + interval '30 minutes', 'HELD')""",
                Identifiers.newId(), sku, order, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("exceed");
        // A SKU reservation is a customer order's and is never ALLOCATED on a stock row.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id,
                                                         quantity, reserved_at, status, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, NOW(), 'ALLOCATED', NOW())""", Identifiers.newId(),
                rows.get("HCM-A01-1-A").get(0), sku, order, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id,
                                                         quantity, reserved_at, status)
                VALUES (?, ?, 'SOFA-3S-GREY', ?, ?, ?, 1, NOW(), 'HELD')""", Identifiers.newId(),
                rows.get("HCM-A01-1-A").get(0), order, UUID.randomUUID(), UUID.randomUUID())))
                .as("a hold on a stock row holds that row's SKU").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("cancelling before allocation releases the SKU; after, the allocations; ATP comes back either way")
    void cancellationBeforeAndAfterAllocation() {
        UUID early = order();
        Hold soft = reserve(early, 3);
        tx.executeWithoutResult(s -> inventory.release(soft.reservationId(), "ORDER_CANCELLED"));
        assertThat(status(soft.reservationId())).isEqualTo("RELEASED");
        assertThat(atp()).isEqualTo(17);

        UUID late = order();
        Hold released = reserve(late, 6);
        List<UUID> allocations = allocate(late, "HCM").allocations().get(0).holdIds();
        allocations.forEach(id -> tx.executeWithoutResult(s -> inventory.release(id, "ORDER_CANCELLED")));
        assertThat(allocations).allSatisfy(id -> assertThat(status(id)).isEqualTo("RELEASED"));
        assertThat(status(released.reservationId())).as("the reservation it came from stays ALLOCATED").isEqualTo("ALLOCATED");
        tx.executeWithoutResult(s -> inventory.release(released.reservationId(), "ORDER_CANCELLED"));   // a no-op
        assertThat(reservedEverywhere()).isZero();
        assertThat(atp()).isEqualTo(17);
    }

    @Test
    @DisplayName("a short pick's carton is reallocated as the next carton in FIFO order, its own bin's included, never"
            + " the short carton itself (07 BR-09)")
    void reallocationAfterAShortPick() {
        UUID order = order();
        Hold soft = reserve(order, 4);
        StockReservation first = allocate(order, "HCM").allocations().get(0).holds().get(0);
        assertThat(first.locationCode()).isEqualTo("HCM-A01-1-B");

        UUID request = UUID.randomUUID();
        List<StockReservation> again = tx.execute(s -> inventory.reallocate(
                new ReallocateStockCommand(order, first.reservationId(), request)));

        assertThat(again).extracting(StockReservation::locationCode, StockReservation::quantity)
                .as("the short carton's unit, as the bin's free carton: only the short one is in doubt")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM-A01-1-B", 1));
        assertThat(jdbc.queryForObject("SELECT stock_item_id FROM inventory.stock_reservation WHERE id = ?", UUID.class,
                again.get(0).reservationId())).isNotEqualTo(jdbc.queryForObject(
                "SELECT stock_item_id FROM inventory.stock_reservation WHERE id = ?", UUID.class,
                first.reservationId()));
        assertThat(jdbc.queryForObject("SELECT status || ':' || release_reason FROM inventory.stock_reservation WHERE id = ?",
                String.class, first.reservationId())).isEqualTo("RELEASED:SHORT_PICKED");
        assertThat(jdbc.queryForObject("SELECT allocated_from_id FROM inventory.stock_reservation WHERE id = ?",
                UUID.class, again.get(0).reservationId())).isEqualTo(soft.reservationId());
        List<StockReservation> replayed = tx.execute(s -> inventory.reallocate(new ReallocateStockCommand(order,
                first.reservationId(), request)));
        assertThat(replayed).as("replayed").extracting(StockReservation::reservationId)
                .containsExactly(again.get(0).reservationId());
        assertThat(code(catchThrowable(() -> tx.execute(s -> inventory.reallocate(
                new ReallocateStockCommand(order(), again.get(0).reservationId(), UUID.randomUUID()))))))
                .as("another order's allocation").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThat(reservedEverywhere()).isEqualTo(4);
    }

    @Test
    @DisplayName("BE-3 (08 §4.2 step 4): a carton picked and then removed at the check station is replaced by the next "
            + "carton in FIFO order, never itself; its hold stays CONSUMED; a replay answers with the replacement, even "
            + "once that was picked; a live hold is not replaced this way")
    void replacementOfACartonRemovedAtTheCheckStation() {
        UUID order = order();
        Hold soft = reserve(order, 2);
        List<StockReservation> held = allocate(order, "HCM").allocations().get(0).holds();
        StockReservation removed = held.get(0);
        UUID request = UUID.randomUUID();
        assertThat(code(catchThrowable(() -> tx.execute(s -> inventory.reallocateRemoved(
                new ReallocateStockCommand(order, removed.reservationId(), request))))))
                .as("a hold not picked is a short pick's to reallocate").isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
        UUID picker = jdbc.queryForObject("SELECT id FROM identity.app_user ORDER BY id LIMIT 1", UUID.class);
        tx.executeWithoutResult(s -> consumption.consumeForPick(removed.reservationId(), UUID.randomUUID(), picker));

        List<StockReservation> replacement = tx.execute(s -> inventory.reallocateRemoved(
                new ReallocateStockCommand(order, removed.reservationId(), request)));

        assertThat(replacement).singleElement().satisfies(r -> {
            assertThat(r.quantity()).isEqualTo(1);
            assertThat(r.stockItemId()).isNotEqualTo(removed.stockItemId());
            assertThat(held).extracting(StockReservation::stockItemId).doesNotContain(r.stockItemId());
        });
        assertThat(status(removed.reservationId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT allocated_from_id FROM inventory.stock_reservation WHERE id = ?",
                UUID.class, replacement.get(0).reservationId())).isEqualTo(soft.reservationId());
        tx.executeWithoutResult(s -> consumption.consumeForPick(replacement.get(0).reservationId(), UUID.randomUUID(),
                picker));
        List<StockReservation> replayed = tx.execute(s -> inventory.reallocateRemoved(new ReallocateStockCommand(order,
                removed.reservationId(), request)));
        assertThat(replayed).as("replayed after the replacement was picked")
                .extracting(StockReservation::reservationId).containsExactly(replacement.get(0).reservationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ? "
                + "AND status IN ('HELD', 'CONSUMED') AND stock_item_id IS NOT NULL", Integer.class, order))
                .as("the two allocated, and one replacement - never a second").isEqualTo(3);
    }

    @Test
    @DisplayName("a short pick opens one blind recount of the carton; the counter does not see the books")
    void shortPickOpensOneBlindRecount() {
        UUID order = order();
        reserve(order, 4);
        StockReservation allocation = allocate(order, "HCM").allocations().get(0).holds().get(0);

        var first = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 0));
        var again = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 0));

        assertThat(first.created()).isTrue();
        assertThat(first.countNumber()).startsWith("CC-");
        assertThat(again.created()).as("one open recount per carton").isFalse();
        assertThat(again.cycleCountId()).isEqualTo(first.cycleCountId());
        var listed = cycleCounts.list("planned", DemoData.WAREHOUSE_HCM, false, 0, 200).items().stream()
                .filter(c -> c.id().equals(first.cycleCountId())).findFirst().orElseThrow();
        assertThat(listed.triggerType()).isEqualTo("SHORT_PICK");
        assertThat(listed.lineCount()).as("the list carries no lines, only how many").isEqualTo(1);
        assertThat(listed.countedCount()).isZero();
        assertThat(listed.varianceCount()).as("part of the variance: not for a counter").isNull();
        assertThat(listed.note()).as("blind in the list too").isNull();
        assertThat(cycleCounts.list(null, DemoData.WAREHOUSE_HCM, true, 0, 200).items().stream()
                .filter(c -> c.id().equals(first.cycleCountId())).findFirst().orElseThrow().varianceCount())
                .as("for who decides").isZero();
        var counter = cycleCounts.get(first.cycleCountId(), false);
        assertThat(counter.triggerType()).isEqualTo("SHORT_PICK");
        assertThat(counter.lines()).singleElement().satisfies(line -> {
            assertThat(line.locationCode()).isEqualTo(allocation.locationCode());
            assertThat(line.sku()).isEqualTo(sku);
            assertThat(line.stockPackageId()).as("a row of a carton is counted as that carton")
                    .isEqualTo(cartonOf(allocation));
            assertThat(line.booked()).as("blind").isNull();
        });
        var manager = cycleCounts.get(first.cycleCountId(), true);
        assertThat(manager.lines().get(0).booked()).as("the carton's books, for who decides").isTrue();
        assertThat(manager.note()).contains("found 0");
        assertThat(counter.note()).as("what the picker found would anchor the count too").isNull();
        assertThat(jdbc.queryForList("""
                SELECT p.code FROM identity.role_permission rp JOIN identity.app_role ro ON ro.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE ro.code = 'WAREHOUSE_STAFF' AND p.resource = 'inventory-cycle-counts'""", String.class))
                .as("docs 04c F-CNT: the warehouse staff count").containsExactlyInAnyOrder(
                        "inventory-cycle-counts:VIEW_PAGE", "inventory-cycle-counts:READ", "inventory-cycle-counts:UPDATE");

        // The recount doubts the short carton only, and the order holds it whole: nothing free is in doubt, and the
        // bin's other carton stays sellable.
        assertThat(allocation.locationCode()).isEqualTo("HCM-A01-1-B");
        assertThat(atp()).as("17 - 4 reserved; the doubted carton has no free unit").isEqualTo(13);
        UUID next = order();
        reserve(next, 1);
        StockReservation nextHold = allocate(next, "HCM").allocations().get(0).holds().get(0);
        assertThat(nextHold.locationCode()).as("FIFO: the bin's last free carton").isEqualTo("HCM-A01-1-B");
        assertThat(nextHold.stockItemId()).isNotEqualTo(allocation.stockItemId());

        var cancelled = tx.execute(s -> cycleCounts.cancel(first.cycleCountId(), "Bin checked by hand", "manager"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.note()).endsWith("Cancelled: Bin checked by hand");
        assertThat(atp()).as("sellable again: 17 - 4 - 1 reserved by the next order").isEqualTo(12);
        assertThat(tx.execute(s -> cycleCounts.cancel(first.cycleCountId(), "again", "manager")).status())
                .as("a no-op").isEqualTo("CANCELLED");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.cycle_count SET status = 'COMPLETED',"
                + " started_at = NOW(), completed_at = NOW() WHERE id = ?", first.cycleCountId()));
        assertThat(code(catchThrowable(() -> tx.execute(s -> cycleCounts.cancel(first.cycleCountId(), "late",
                "manager"))))).isEqualTo(ErrorCode.CYCLE_COUNT_NOT_OPEN);
        assertThat(code(catchThrowable(() -> cycleCounts.list("LOST", null, false, 0, 20))))
                .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
        var elsewhere = new com.stockflow.common.security.CurrentUser(UUID.randomUUID(), "kho",
                Set.of(com.stockflow.common.security.Role.WAREHOUSE_STAFF), Set.of(),
                com.stockflow.common.security.DataScope.WAREHOUSE, Set.of("ZZZ"), Set.of(UUID.randomUUID()));
        assertThat(com.stockflow.support.WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> cycleCounts.list(null, null, false, 0, 20)).items()).as("another warehouse's clerk").isEmpty();
        assertThat(code(catchThrowable(() -> com.stockflow.support.WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> cycleCounts.list(null, DemoData.WAREHOUSE_HCM, false, 0, 20)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
    }

    /** A MANUAL count of one bin of this SKU, planned below HTTP (no caller scope: the system). */
    private com.stockflow.inventory.internal.service.CycleCountView planBin(String location) {
        return cycleCounts.plan(new com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand(
                DemoData.WAREHOUSE_HCM, null, null, true, "Shelf check", List.of(), List.of(), List.of(location),
                List.of(sku)), false).count();
    }

    private int countsOf(String trigger) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT c.id) FROM inventory.cycle_count c
                  JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE l.sku = ? AND c.trigger_type = ?""", Integer.class, sku, trigger);
    }

    @Test
    @DisplayName("a short pick on a carton whose MANUAL count's shortage waits for approval reuses that count; a "
            + "free carton counted short is frozen by its pending shortage")
    void shortPickOnAPendingManualShortageReusesItsCount() {
        UUID counter = tx.execute(s -> com.stockflow.support.ReferenceRows.user(entityManager));
        UUID order = order();
        reserve(order, 4);
        StockReservation allocation = allocate(order, "HCM").allocations().get(0).holds().get(0);
        assertThat(allocation.locationCode()).isEqualTo("HCM-A01-1-B");
        var manual = planBin("HCM-A01-1-B");
        // The bin's five cartons are five lines: the allocated carton and the bin's one free carton are found missing.
        UUID shortCarton = cartonOf(allocation);
        UUID freeCarton = jdbc.queryForObject("""
                SELECT stock_package_id FROM inventory.stock_item
                 WHERE sku = ? AND location_code = 'HCM-A01-1-B' AND reserved = 0""", UUID.class, sku);
        // Every line is counted before the count is submitted: those two at 0, the bin's other cartons as they are.
        for (var line : manual.lines()) {
            boolean missing = shortCarton.equals(line.stockPackageId()) || freeCarton.equals(line.stockPackageId());
            cycleCounts.count(manual.id(), line.id(), !missing, counter, false);
        }
        cycleCounts.submit(manual.id(), counter, false);
        assertThat(jdbc.queryForList("""
                SELECT quantity_delta FROM inventory.stock_adjustment WHERE sku = ? AND status = 'PENDING_APPROVAL'
                   AND reason_code = 'COUNT_VARIANCE'""", Integer.class, sku)).containsExactly(-1, -1);
        assertThat(atp()).as("17 - 4 reserved - the free carton a pending shortage doubts").isEqualTo(12);

        var recount = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 3));

        assertThat(recount.created()).as("the carton is busy: its variance waits for approval").isFalse();
        assertThat(recount.cycleCountId()).isEqualTo(manual.id());
        assertThat(recount.countNumber()).isEqualTo(manual.number());
        assertThat(countsOf("SHORT_PICK")).as("no second count of the same stock").isZero();
        assertThat(atp()).as("still frozen").isEqualTo(12);
        // A carton a row: the bin's held cartons are listed with nothing available, and its free one not at all.
        assertThat(inventory.availabilityOf(new Sku(sku))).filteredOn(a -> "HCM-A01-1-B".equals(a.locationCode()))
                .as("its one free carton is frozen").extracting(StockAvailability::available).containsOnly(0);
        assertThat(inventory.availabilityOf(new Sku(sku))).extracting(StockAvailability::stockItemId)
                .as("the frozen carton is not offered").doesNotContain(jdbc.queryForObject(
                        "SELECT id FROM inventory.stock_item WHERE stock_package_id = ?", UUID.class, freeCarton));
    }

    @Test
    @DisplayName("a short pick on a carton an open MANUAL count holds reuses that count; the slot stays sellable until "
            + "the count is submitted (the accepted window)")
    void shortPickOnAnOpenManualCountReusesIt() {
        UUID order = order();
        reserve(order, 4);
        StockReservation allocation = allocate(order, "HCM").allocations().get(0).holds().get(0);
        var manual = planBin(allocation.locationCode());

        var recount = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 3));

        assertThat(recount.created()).isFalse();
        assertThat(recount.cycleCountId()).isEqualTo(manual.id());
        assertThat(countsOf("SHORT_PICK")).isZero();
        assertThat(atp()).as("a MANUAL count freezes nothing while open: 17 - 4 reserved").isEqualTo(13);
    }
}
