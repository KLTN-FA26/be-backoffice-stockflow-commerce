package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReallocateStockCommand;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
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

import java.sql.Timestamp;
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
 * warehouse allocates it to pickable bins of that warehouse by BR-STK-006 (earliest expiry, then the earliest real
 * receipt time), never on expired stock, a non-pickable area or another warehouse; the SKU never promises more
 * than it has, under concurrency too, and the database refuses what the application would not.
 *
 * <p>Each test owns a fresh SKU with this stock (today = the business date):</p>
 * <pre>
 *   HCM-A01-1-A  bin   5  expires today+30   received 2026-01-01
 *   HCM-A01-1-B  bin   5  expires today+10   received 2026-02-01   (earliest expiry)
 *   HCM-A01-2-A  bin   5  no expiry          received 2025-12-01
 *   HCM-A02-2-A  bin   2  no expiry          received 2025-11-01   (oldest receipt)
 *   HCM-RCV01    area  5  expires today+1    not pickable: never allocated, though sellable
 *   HCM-A02-1-A  bin   4  expired yesterday  never promised, never allocated
 * </pre>
 * Available to promise is therefore 22.
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
    @Autowired com.stockflow.inventory.internal.service.TransferOrders transfers;

    private String sku;
    private final Map<String, UUID> rows = new java.util.HashMap<>();
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
            row("HCM-A01-1-A", 5, today.plusDays(30), "2026-01-01T00:00:00Z");
            row("HCM-A01-1-B", 5, today.plusDays(10), "2026-02-01T00:00:00Z");
            row("HCM-A01-2-A", 5, null, "2025-12-01T00:00:00Z");
            row("HCM-A02-2-A", 2, null, "2025-11-01T00:00:00Z");
            row("HCM-RCV01", 5, today.plusDays(1), "2026-03-01T00:00:00Z");
            row("HCM-A02-1-A", 4, today.minusDays(1), "2025-10-01T00:00:00Z");
        });
    }

    private void row(String location, int onHand, LocalDate expiry, String receivedAt) {
        UUID id = Identifiers.newId();
        rows.put(location, id);
        jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, expiry_date, on_hand, reserved, status,
                                                  received_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, 0, 'AVAILABLE', ?, 0, NOW())""",
                id, sku, location, expiry, onHand, Timestamp.from(Instant.parse(receivedAt)));
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

    private StockReservation reserve(UUID order, int quantity) {
        return reserve(order, UUID.randomUUID(), quantity);
    }

    private StockReservation reserve(UUID order, UUID request, int quantity) {
        return tx.execute(s -> {
            inventory.prepareReservation(Set.of(new Sku(sku)));
            return inventory.reserve(new ReserveStockCommand(request, new Sku(sku), quantity, order))
                    .reservations().get(0);
        });
    }

    private AllocateStockResult allocate(UUID order, String warehouse) {
        return tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, warehouse)));
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    private int reservedOn(String location) {
        return jdbc.queryForObject("SELECT reserved FROM inventory.stock_item WHERE id = ?", Integer.class,
                rows.get(location));
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
    @DisplayName("checkout holds the SKU on no location: ATP falls, no stock row is held, a replay is the same hold")
    void checkoutReservesTheSkuOnly() {
        assertThat(atp()).isEqualTo(22);
        UUID order = order();
        UUID request = UUID.randomUUID();
        StockReservation held = reserve(order, request, 7);

        assertThat(held.stockItemId()).isNull();
        assertThat(held.locationCode()).isNull();
        assertThat(held.quantity()).isEqualTo(7);
        assertThat(atp()).isEqualTo(15);
        assertThat(reservedEverywhere()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT stock_item_id, sku, status, expires_at IS NOT NULL AS deadline FROM inventory.stock_reservation
                 WHERE id = ?""", held.reservationId()))
                .containsEntry("stock_item_id", null).containsEntry("sku", sku).containsEntry("status", "HELD")
                .containsEntry("deadline", true);

        assertThat(reserve(order, request, 7).reservationId()).isEqualTo(held.reservationId());
        assertThat(atp()).isEqualTo(15);
        assertThat(inventory.holdsOf(order)).singleElement()
                .satisfies(hold -> assertThat(hold.locationCode()).isNull());
        assertThat(inventory.holds(List.of(held.reservationId()))).singleElement()
                .satisfies(view -> assertThat(view.status()).isEqualTo(HoldView.Status.HELD));
        assertThat(code(catchThrowable(() -> reserve(order(), 16)))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("release allocates FEFO on pickable bins: earliest expiry, then the oldest receipt; never an area")
    void allocationFollowsFefoThenFifoOnPickableBins() {
        UUID order = order();
        StockReservation soft = reserve(order, 12);
        int atpBefore = atp();

        AllocateStockResult result = allocate(order, "hcm");

        assertThat(result.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.reservationId()).isEqualTo(soft.reservationId());
            assertThat(allocation.holds()).extracting(StockReservation::locationCode, StockReservation::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM-A01-1-B", 5),
                            org.assertj.core.groups.Tuple.tuple("HCM-A01-1-A", 5),
                            org.assertj.core.groups.Tuple.tuple("HCM-A02-2-A", 2));
        });
        assertThat(reservedOn("HCM-A01-1-B")).isEqualTo(5);
        assertThat(reservedOn("HCM-A01-1-A")).isEqualTo(5);
        assertThat(reservedOn("HCM-A02-2-A")).isEqualTo(2);
        assertThat(reservedOn("HCM-A01-2-A")).isZero();
        assertThat(reservedOn("HCM-RCV01")).as("not pickable").isZero();
        assertThat(reservedOn("HCM-A02-1-A")).as("expired").isZero();
        assertThat(status(soft.reservationId())).isEqualTo("ALLOCATED");
        assertThat(atp()).as("an allocation moves units from reserved to held, it does not promise more").isEqualTo(atpBefore);
        assertThat(jdbc.queryForList("""
                SELECT allocated_from_id::text || ':' || (expires_at IS NULL) FROM inventory.stock_reservation
                 WHERE allocated_from_id IS NOT NULL AND order_id = ?""", String.class, order))
                .hasSize(3).containsOnly(soft.reservationId() + ":true");

        assertThat(recorded.matching(com.stockflow.contracts.StockAllocated.class, e -> e.orderId().equals(order)))
                .as("one StockAllocated per bin and lot")
                .extracting(com.stockflow.contracts.StockAllocated::locationCode,
                        com.stockflow.contracts.StockAllocated::quantity)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("HCM-A01-1-B", 5),
                        org.assertj.core.groups.Tuple.tuple("HCM-A01-1-A", 5),
                        org.assertj.core.groups.Tuple.tuple("HCM-A02-2-A", 2));
        assertThat(allocate(order, "HCM").allocations()).as("allocating again changes nothing").isEmpty();
        assertThat(reservedEverywhere()).isEqualTo(12);
        // SCRUM-476: Inventory Level shows the 12 allocated units apart from the other holds, ATP unchanged.
        assertThat(inventory.levelsOf(new Sku(sku)))
                .containsExactly(new com.stockflow.inventory.api.StockLevel(sku, "HCM", 26, 22, 0, 12, 10));
    }

    @Test
    @DisplayName("a warehouse that cannot cover the order allocates nothing and leaves the reservation held")
    void allocationIsAllOrNothing() {
        UUID order = order();
        StockReservation soft = reserve(order, 13);   // 17 pickable in HCM: enough; but not 18
        UUID tooMuch = order();
        StockReservation big = reserve(tooMuch, 9);   // ATP 22 - 13 = 9; together 22 > 17 pickable

        assertThat(allocate(order, "HCM").allocations()).hasSize(1);
        assertThat(code(catchThrowable(() -> allocate(tooMuch, "HCM")))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(status(big.reservationId())).isEqualTo("HELD");
        assertThat(code(catchThrowable(() -> allocate(tooMuch, "ZZZ")))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(reservedEverywhere()).isEqualTo(13);
        assertThat(status(soft.reservationId())).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("a release after one line's reservation lapsed allocates nothing, rather than the other lines alone")
    void aLapsedReservationStopsTheAllocation() {
        UUID order = order();
        StockReservation kept = reserve(order, 2);
        StockReservation lapsed = reserve(order, 3);
        tx.executeWithoutResult(s -> inventory.release(lapsed.reservationId(), "MANUAL_OVERRIDE"));

        assertThat(code(catchThrowable(() -> allocate(order, "HCM")))).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
        assertThat(status(kept.reservationId())).isEqualTo("HELD");
        assertThat(reservedEverywhere()).isZero();

        UUID none = order();
        StockReservation only = reserve(none, 1);
        tx.executeWithoutResult(s -> inventory.release(only.reservationId(), "MANUAL_OVERRIDE"));
        assertThat(code(catchThrowable(() -> allocate(none, "HCM")))).as("nothing left to allocate either")
                .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
    }

    @Test
    @DisplayName("concurrent checkouts never promise more than the SKU has")
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
                    try {
                        reserve(order, 4);
                        return true;
                    } catch (BusinessException refused) {
                        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                        return false;
                    }
                };
                results.add(pool.submit(checkout));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                succeeded += result.get() ? 1 : 0;
            }
            assertThat(succeeded).isEqualTo(5);   // 5 x 4 = 20 <= 22 < 24
        } finally {
            pool.shutdownNow();
        }
        assertThat(atp()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT sum(quantity) FROM inventory.stock_reservation
                 WHERE sku = ? AND stock_item_id IS NULL AND status = 'HELD'""", Integer.class, sku)).isEqualTo(20);
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
                rows.get("HCM-A01-1-A"), sku, order, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id,
                                                         quantity, reserved_at, status)
                VALUES (?, ?, 'SOFA-3S-GREY', ?, ?, ?, 1, NOW(), 'HELD')""", Identifiers.newId(),
                rows.get("HCM-A01-1-A"), order, UUID.randomUUID(), UUID.randomUUID())))
                .as("a hold on a stock row holds that row's SKU").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("cancelling before allocation releases the SKU; after, the allocations; ATP comes back either way")
    void cancellationBeforeAndAfterAllocation() {
        UUID early = order();
        StockReservation soft = reserve(early, 3);
        tx.executeWithoutResult(s -> inventory.release(soft.reservationId(), "ORDER_CANCELLED"));
        assertThat(status(soft.reservationId())).isEqualTo("RELEASED");
        assertThat(atp()).isEqualTo(22);

        UUID late = order();
        StockReservation released = reserve(late, 6);
        List<UUID> allocations = allocate(late, "HCM").allocations().get(0).holdIds();
        allocations.forEach(id -> tx.executeWithoutResult(s -> inventory.release(id, "ORDER_CANCELLED")));
        assertThat(allocations).allSatisfy(id -> assertThat(status(id)).isEqualTo("RELEASED"));
        assertThat(status(released.reservationId())).as("the reservation it came from stays ALLOCATED").isEqualTo("ALLOCATED");
        tx.executeWithoutResult(s -> inventory.release(released.reservationId(), "ORDER_CANCELLED"));   // a no-op
        assertThat(reservedEverywhere()).isZero();
        assertThat(atp()).isEqualTo(22);
    }

    @Test
    @DisplayName("payment pins a SKU reservation, a refused credit re-arms it; allocations never expire")
    void pinAndRearm() {
        UUID order = order();
        StockReservation soft = reserve(order, 2);
        assertThat((Integer) tx.execute(s -> inventory.pinReservations(order))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM inventory.stock_reservation WHERE id = ?",
                Boolean.class, soft.reservationId())).isTrue();
        assertThat((Integer) tx.execute(s -> inventory.rearmReservations(order))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expires_at IS NOT NULL FROM inventory.stock_reservation WHERE id = ?",
                Boolean.class, soft.reservationId())).isTrue();

        allocate(order, "HCM");
        assertThat((Integer) tx.execute(s -> inventory.rearmReservations(order))).as("allocations stay pinned").isZero();
    }

    @Test
    @DisplayName("a short pick is reallocated FEFO elsewhere in the warehouse, never on the short bin")
    void reallocationAfterAShortPick() {
        UUID order = order();
        StockReservation soft = reserve(order, 4);
        StockReservation first = allocate(order, "HCM").allocations().get(0).holds().get(0);
        assertThat(first.locationCode()).isEqualTo("HCM-A01-1-B");

        UUID request = UUID.randomUUID();
        List<StockReservation> again = tx.execute(s -> inventory.reallocate(
                new ReallocateStockCommand(order, first.reservationId(), request)));

        assertThat(again).extracting(StockReservation::locationCode, StockReservation::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM-A01-1-A", 4));
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
    @DisplayName("a short pick opens one blind recount of the bin, SKU and lot; the counter does not see the books")
    void shortPickOpensOneBlindRecount() {
        UUID order = order();
        reserve(order, 4);
        StockReservation allocation = allocate(order, "HCM").allocations().get(0).holds().get(0);

        var first = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 1));
        var again = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 0));

        assertThat(first.created()).isTrue();
        assertThat(first.countNumber()).startsWith("CC-");
        assertThat(again.created()).as("one open recount per bin, SKU and lot").isFalse();
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
            assertThat(line.expectedQty()).as("blind").isNull();
        });
        var manager = cycleCounts.get(first.cycleCountId(), true);
        assertThat(manager.lines().get(0).expectedQty()).as("the books, for who decides").isEqualTo(5);
        assertThat(manager.note()).contains("found 1");
        assertThat(counter.note()).as("what the picker found would anchor the count too").isNull();
        assertThat(jdbc.queryForList("""
                SELECT p.code FROM identity.role_permission rp JOIN identity.app_role ro ON ro.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE ro.code = 'WAREHOUSE_STAFF' AND p.resource = 'inventory-cycle-counts'""", String.class))
                .as("docs 04c F-CNT: the warehouse staff count").containsExactlyInAnyOrder(
                        "inventory-cycle-counts:VIEW_PAGE", "inventory-cycle-counts:READ", "inventory-cycle-counts:UPDATE");

        // The short bin's free unit is in doubt: not promised, not allocated, until the count is called off.
        assertThat(allocation.locationCode()).isEqualTo("HCM-A01-1-B");
        assertThat(atp()).as("22 - 4 reserved - 1 free in doubt at HCM-A01-1-B").isEqualTo(17);
        UUID next = order();
        reserve(next, 1);
        assertThat(allocate(next, "HCM").allocations().get(0).holds()).extracting(StockReservation::locationCode)
                .as("FEFO would take HCM-A01-1-B first").containsExactly("HCM-A01-1-A");
        assertThat(inventory.availabilityOf(new Sku(sku))).extracting(StockAvailability::locationCode)
                .doesNotContain("HCM-A01-1-B");

        var cancelled = tx.execute(s -> cycleCounts.cancel(first.cycleCountId(), "Bin checked by hand", "manager"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.note()).endsWith("Cancelled: Bin checked by hand");
        assertThat(atp()).as("sellable again: 22 - 4 - 1 reserved by the next order").isEqualTo(17);
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
    @DisplayName("a short pick on a slot whose MANUAL count's shortage waits for approval reuses that count; the "
            + "bin's free units are frozen by the pending shortage")
    void shortPickOnAPendingManualShortageReusesItsCount() {
        UUID counter = tx.execute(s -> com.stockflow.support.ReferenceRows.user(entityManager));
        UUID order = order();
        reserve(order, 4);
        StockReservation allocation = allocate(order, "HCM").allocations().get(0).holds().get(0);
        assertThat(allocation.locationCode()).isEqualTo("HCM-A01-1-B");
        var manual = planBin("HCM-A01-1-B");
        cycleCounts.count(manual.id(), manual.lines().get(0).id(), 4, null, counter, false);
        cycleCounts.submit(manual.id(), counter, false);
        assertThat(jdbc.queryForObject("""
                SELECT quantity_delta FROM inventory.stock_adjustment WHERE sku = ? AND status = 'PENDING_APPROVAL'
                   AND reason_code = 'COUNT_VARIANCE'""", Integer.class, sku)).isEqualTo(-1);
        assertThat(atp()).as("22 - 4 reserved - the 1 free unit a pending shortage doubts").isEqualTo(17);

        var recount = tx.execute(s -> consumption.requestRecount(allocation.reservationId(), UUID.randomUUID(), 3));

        assertThat(recount.created()).as("the slot is busy: its variance waits for approval").isFalse();
        assertThat(recount.cycleCountId()).isEqualTo(manual.id());
        assertThat(recount.countNumber()).isEqualTo(manual.number());
        assertThat(countsOf("SHORT_PICK")).as("no second count of the same stock").isZero();
        assertThat(atp()).as("still frozen").isEqualTo(17);
        assertThat(inventory.availabilityOf(new Sku(sku))).extracting(StockAvailability::locationCode)
                .doesNotContain("HCM-A01-1-B");
    }

    @Test
    @DisplayName("a short pick on a slot an open MANUAL count holds reuses that count; the slot stays sellable until "
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
        assertThat(atp()).as("a MANUAL count freezes nothing while open: 22 - 4 reserved").isEqualTo(18);
    }

    @Test
    @DisplayName("a production hold cannot take the units checkout reservations promised")
    void productionHoldsRespectSkuReservations() {
        reserve(order(), 20);
        assertThat(atp()).isEqualTo(2);
        UUID productionOrder = Identifiers.newId();
        assertThat(code(catchThrowable(() -> tx.execute(s -> inventory.reserveForProduction(
                new ReserveProductionStockCommand(UUID.randomUUID(), new Sku(sku), 3, productionOrder, "HCM"))))))
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(reservedEverywhere()).isZero();
    }

    @Test
    @DisplayName("issue 207, doc 10 step 2: a transfer is created only for what is left after checkout reservations")
    void transfersRespectSkuReservations() {
        UUID hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID destination = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Transfer destination', 'ACTIVE', ?, 'Test address', 'M', 50, 50, 0, NOW())""",
                destination, "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
        reserve(order(), 20);   // 22 sellable in HCM, 2 left once the promise is counted
        java.util.function.IntFunction<com.stockflow.inventory.internal.service.TransferOrders.Transfer> transfer =
                quantity -> transfers.create(new com.stockflow.inventory.internal.service.TransferOrders.Create(hcm,
                        destination, com.stockflow.inventory.internal.domain.TransferReason.REBALANCING, null,
                        List.of(new com.stockflow.inventory.internal.service.TransferOrders.NewLine(sku, null, quantity)),
                        com.stockflow.inventory.api.TransferDeliveryMode.PARCEL));

        assertThat(code(catchThrowable(() -> transfer.apply(3)))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.transfer_order_line WHERE sku = ?""", Integer.class, sku)).isZero();
        assertThat(transfer.apply(2).status())
                .isEqualTo(com.stockflow.inventory.internal.domain.TransferStatus.DRAFT);
    }
}
