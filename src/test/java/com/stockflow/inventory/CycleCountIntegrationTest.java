package com.stockflow.inventory;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.identity.api.AssignableUser;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.AddCountLineCommand;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.inventory.internal.service.CycleCounts.PlannedCount;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * SCRUM-146 end to end below HTTP, on the demo warehouse {@code HCM} and a real PostgreSQL, by carton (team plan 4C-3;
 * kltn-docs 20 §4.2): a count is planned over a scope, one line per LPN carton in stock; each carton is scanned found
 * or not found against the books as they stand at counting (BR-09); submission turns every carton found against the
 * books into one whole-carton {@code COUNT_VARIANCE} adjustment that a second person approves; a doubted variance is
 * rejected and recounted. Nothing is counted in units.
 *
 * <p>The database is shared by every test class, so each test owns a fresh SKU (FEFO, lot and expiry tracked, with a
 * carton unit of 50) and every plan is narrowed to it: a shelf-wide plan would also claim other tests' stock on the
 * demo shelves. The SKU filter is itself part of the scope rules under test. Counts get their numbers from the
 * {@code CC} sequence, so they are unique per run.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CycleCountIntegrationTest {

    /** Shelf A01, levels 1 and 2; A02 is the other shelf of zone A; B01 is zone B. */
    private static final String BIN = "HCM-A01-1-B";
    private static final String BIN2 = "HCM-A01-2-A";
    private static final String BIN3 = "HCM-A01-1-A";
    private static final String A02_BIN = "HCM-A02-2-A";
    private static final String B01_BIN = "HCM-B01-1-A";
    private static final String AREA = "HCM-RCV01";
    private static final Instant EARLY = Instant.parse("2026-01-02T00:00:00Z");
    private static final int CARTON_FACTOR = 50;

    @Autowired CycleCounts cycleCounts;
    @Autowired CycleCountRepository repository;
    @Autowired StockOperations operations;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
    private UUID item;
    private UUID cartonUnit;
    /** Who counts and submits; never the approver (four eyes). */
    private UUID counter;
    private UUID manager;

    @BeforeEach
    void setUp() {
        sku = "CCI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "LOT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        counter = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Cycle count fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Cycle count fixture");
            jdbc.update("update inventory.inventory_items set lot_tracked=true, expiry_tracked=true,"
                    + " removal_strategy='FEFO', policy_configured=true where sku=?", sku);
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            cartonUnit = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', ?)", cartonUnit, item, CARTON_FACTOR);
        });
    }

    // ------------------------------------------------------------------ fixtures

    /** A row of loose units in no carton: the history's shape, which no count plans any more. */
    private UUID loose(String location, int onHand) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at, on_hand,
                    reserved, status, version, created_at) values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                id, sku, location, lot, Date.valueOf(LocalDate.now().plusYears(1)), Timestamp.from(EARLY), onHand));
        return id;
    }

    /** A whole carton of the SKU's standard count at {@code location}, IN_STOCK, with its one row holding all of it. */
    private UUID carton(String location, String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status) values (?, ?, ?, ?, ?, ?, ?, 'IN_STOCK')""",
                    id, lpn(name), item, cartonUnit, CARTON_FACTOR, CARTON_FACTOR, location);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at,
                        on_hand, reserved, status, version, created_at, stock_package_id)
                    values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), sku, location, lot, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(EARLY), CARTON_FACTOR, id);
        });
        return id;
    }

    /**
     * The carton and its rows moved whole to another bin, in one transaction as the commit-time carton check requires
     * (as {@code StockPackageSchemaMigrationTest} moves one).
     */
    private void moveWhole(UUID carton, String location) {
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_package set location_code = ? where id = ?", location, carton);
            jdbc.update("update inventory.stock_item set location_code = ? where stock_package_id = ?", location,
                    carton);
        });
    }

    /** The carton emptied as a last pick would leave it, in one transaction for the commit-time carton check. */
    private void consumeWhole(UUID carton) {
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_item set on_hand = 0 where stock_package_id = ?", carton);
            jdbc.update("update inventory.stock_package set status = 'CONSUMED', closed_at = now() where id = ?",
                    carton);
        });
    }

    /** The carton gone missing, as an approved count shortage leaves it. */
    private void loseWhole(UUID carton) {
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_item set on_hand = 0 where stock_package_id = ?", carton);
            jdbc.update("update inventory.stock_package set status = 'LOST', closed_at = now() where id = ?", carton);
        });
    }

    /** A LOST carton found again, whole, on the row it was emptied from. */
    private void findWhole(UUID carton) {
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_package set status = 'IN_STOCK', closed_at = null where id = ?",
                    carton);
            jdbc.update("update inventory.stock_item set on_hand = ? where stock_package_id = ?", CARTON_FACTOR,
                    carton);
        });
    }

    private String lpn(String name) {
        return "LPN-" + sku + "-" + name;
    }

    /** An active account holding one role, assigned to a warehouse when given one. */
    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    /** A SHORT_PICK recount as a pick opens it ({@code PickConsumption.requestRecount}), on one carton. */
    private CycleCount shortPick(UUID carton, String location) {
        return tx.execute(s -> {
            LocalDate day = BusinessCalendar.date(Instant.now());
            return repository.save(CycleCount.shortPick(repository.nextNumber(day), WAREHOUSE_HCM,
                    cartonSlot(carton, location), CARTON_FACTOR, "Short pick: found 1", day));
        });
    }

    private PlannedCount plan(List<UUID> zones, List<String> shelves, List<String> codes, List<String> skus) {
        return plan(null, zones, shelves, codes, skus);
    }

    private PlannedCount plan(UUID assignee, List<UUID> zones, List<String> shelves, List<String> codes,
                              List<String> skus) {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, assignee, true, "Fixture count",
                zones, shelves, codes, skus), false);
    }

    /** Shelf A01, this test's SKU only. */
    private PlannedCount planShelfA01() {
        return plan(List.of(), List.of("A01"), List.of(), List.of(sku));
    }

    private PlannedCount planBin(String location) {
        return plan(List.of(), List.of(), List.of(location), List.of(sku));
    }

    private CountSlot cartonSlot(UUID carton, String location) {
        return CountSlot.carton(new LocationId(location), new Sku(sku), lot, new StockPackageId(carton));
    }

    private static CycleCountView.Line cartonLine(CycleCountView view, UUID carton) {
        return view.lines().stream().filter(l -> carton.equals(l.stockPackageId())).findFirst().orElseThrow();
    }

    private static Set<String> slots(CycleCountView view) {
        return view.lines().stream().map(l -> l.locationCode() + "|" + l.sku() + "|" + l.lotNumber() + "|"
                + l.stockPackageId()).collect(Collectors.toSet());
    }

    private List<Map<String, Object>> adjustments() {
        return jdbc.queryForList("""
                select id, location_code, quantity_delta, status, reason_code, cycle_count_line_id, stock_package_id,
                       requested_by
                  from inventory.stock_adjustment where sku = ? order by created_at, quantity_delta""", sku);
    }

    private int onHand(UUID carton) {
        return jdbc.queryForObject("select coalesce(sum(on_hand), 0) from inventory.stock_item"
                + " where stock_package_id = ?", Integer.class, carton);
    }

    private String status(UUID carton) {
        return jdbc.queryForObject("select status from inventory.stock_package where id = ?", String.class, carton);
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    private List<CycleCountRepository.Frozen> frozen() {
        return repository.frozen(List.of(sku), LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /** Counts every line of the count as found, as a counter scanning the whole bin would. */
    private CycleCountView countAllFound(CycleCountView planned) {
        CycleCountView last = planned;
        for (CycleCountView.Line line : planned.lines()) {
            last = cycleCounts.count(planned.id(), line.id(), true, counter, false);
        }
        return last;
    }

    // ------------------------------------------------------------------ planning (SCRUM-162)

    @Test
    @DisplayName("a shelf plan has one line per carton in stock under that shelf, MANUAL; loose rows are not counted")
    void planByShelfGeneratesOneLinePerCarton() {
        loose(BIN, 5);
        UUID inBin = carton(BIN, "A");
        UUID inBin2 = carton(BIN2, "B");
        carton(A02_BIN, "C");
        UUID consumed = carton(BIN3, "D");
        consumeWhole(consumed);

        PlannedCount planned = planShelfA01();

        CycleCountView count = planned.count();
        assertThat(count.number()).startsWith("CC-");
        assertThat(count.triggerType()).isEqualTo("MANUAL");
        assertThat(count.status()).isEqualTo("PLANNED");
        assertThat(count.blind()).isTrue();
        assertThat(count.warehouseId()).isEqualTo(WAREHOUSE_HCM);
        assertThat(planned.skipped()).isEmpty();
        assertThat(count.lines()).as("a loose row, a consumed carton and shelf A02 are not counted")
                .extracting(CycleCountView.Line::locationCode, CycleCountView.Line::sku,
                        CycleCountView.Line::lotNumber, CycleCountView.Line::stockPackageId)
                .containsExactlyInAnyOrder(tuple(BIN, sku, lot, inBin), tuple(BIN2, sku, lot, inBin2));
        assertThat(count.lines()).as("planning does not make the planner one who decides: no booked state, none found")
                .allSatisfy(line -> {
                    assertThat(line.booked()).isNull();
                    assertThat(line.found()).isNull();
                });

        CycleCountView books = cycleCounts.get(count.id(), true);
        assertThat(cartonLine(books, inBin).booked()).isTrue();
        assertThat(cartonLine(books, inBin).found()).isNull();
        assertThat(cartonLine(books, inBin).lpn()).isEqualTo(lpn("A"));
        assertThat(books.note()).isEqualTo("Fixture count");
        assertThat(jdbc.queryForList("select expected_qty from inventory.cycle_count_line where cycle_count_id = ?",
                Integer.class, count.id())).as("the book of a carton is one whole carton").containsOnly(CARTON_FACTOR);
    }

    @Test
    @DisplayName("a zone plan selects exactly what a plan of its shelves does")
    void planByZoneEqualsPlanByItsShelves() {
        carton(BIN, "A");
        carton(A02_BIN, "B");
        carton(B01_BIN, "C");
        carton(BIN2, "D");

        PlannedCount byZone = plan(List.of(DemoData.ZONE_HCM_A), List.of(), List.of(), List.of(sku));
        Set<String> zoneSlots = slots(byZone.count());
        cycleCounts.cancel(byZone.count().id(), "Compared with the shelves", "test");
        PlannedCount byShelves = plan(List.of(), List.of("A01", "A02"), List.of(), List.of(sku));

        assertThat(zoneSlots).hasSize(3).noneMatch(slot -> slot.startsWith(B01_BIN));
        assertThat(slots(byShelves.count())).isEqualTo(zoneSlots);
        assertThat(code(catchThrowable(() -> plan(List.of(UUID.randomUUID()), List.of(), List.of(), List.of(sku)))))
                .as("a zone not of this warehouse").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a SKU alone means its bins: a carton in a receiving area is not counted")
    void planBySkuOnlyCoversBinsNotAreas() {
        carton(BIN, "A");
        carton(AREA, "R");

        PlannedCount planned = plan(List.of(), List.of(), List.of(), List.of(sku));

        assertThat(planned.count().lines()).extracting(CycleCountView.Line::locationCode).containsExactly(BIN);
    }

    @Test
    @DisplayName("a carton on an open SHORT_PICK recount is skipped and named with the recount's number and LPN")
    void planSkipsBusyCartons() {
        UUID busy = carton(BIN, "A");
        UUID free = carton(BIN2, "B");
        CycleCount recount = shortPick(busy, BIN);

        PlannedCount planned = planShelfA01();

        assertThat(planned.count().lines()).extracting(CycleCountView.Line::stockPackageId).containsExactly(free);
        assertThat(planned.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.locationCode()).isEqualTo(BIN);
            assertThat(skipped.sku()).isEqualTo(sku);
            assertThat(skipped.lotNumber()).isEqualTo(lot);
            assertThat(skipped.lpn()).isEqualTo(lpn("A"));
            assertThat(skipped.countNumber()).isEqualTo(recount.number());
        });
    }

    @Test
    @DisplayName("a scope with no carton is CYCLE_COUNT_EMPTY_SCOPE, one above the cap CYCLE_COUNT_TOO_LARGE (BR-12)")
    void planRefusesEmptyAndTooLarge() {
        loose(BIN, 5);
        assertThat(code(catchThrowable(this::planShelfA01))).as("loose stock alone is no scope")
                .isEqualTo(ErrorCode.CYCLE_COUNT_EMPTY_SCOPE);
        assertThat(code(catchThrowable(() -> plan(List.of(), List.of(), List.of(), List.of()))))
                .as("no criterion at all").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> plan(List.of(), List.of(), List.of(), List.of("not a sku!")))))
                .as("a malformed SKU").isEqualTo(ErrorCode.VALIDATION_FAILED);
        carton(BIN, "A");
        carton(BIN2, "B");

        Object service = AopTestUtils.getUltimateTargetObject(cycleCounts);
        Object cap = ReflectionTestUtils.getField(service, "maxLines");
        assertThat(cap).as("BR-12: 500 cartons a count").isEqualTo(500);
        ReflectionTestUtils.setField(service, "maxLines", 1);
        try {
            assertThat(code(catchThrowable(this::planShelfA01))).isEqualTo(ErrorCode.CYCLE_COUNT_TOO_LARGE);
        } finally {
            ReflectionTestUtils.setField(service, "maxLines", cap);
        }
        assertThat(jdbc.queryForObject("select count(*) from inventory.cycle_count_line where sku = ?", Integer.class,
                sku)).as("nothing planned").isZero();
    }

    // ------------------------------------------------------------------ counting and submission (SCRUM-164)

    @Test
    @DisplayName("a carton found where the books have it has no variance: submitted, nothing is asked")
    void foundWhereBookedIsNoVariance() {
        UUID box = carton(BIN, "K");
        CycleCountView planned = planBin(BIN).count();
        UUID line = planned.lines().get(0).id();

        CycleCountView counted = cycleCounts.count(planned.id(), line, true, counter, false);

        assertThat(counted.status()).isEqualTo("IN_PROGRESS");
        assertThat(counted.startedAt()).isNotNull();
        assertThat(counted.lines().get(0).found()).as("what the counter scanned is theirs to see").isTrue();
        assertThat(counted.lines().get(0).booked()).as("blind").isNull();
        assertThat(counted.lines().get(0).countedBy()).isEqualTo(counter);
        assertThat(counted.lines().get(0).countedAt()).isNotNull();

        CycleCountView submitted = cycleCounts.submit(planned.id(), counter, false);

        assertThat(submitted.status()).isEqualTo("COMPLETED");
        assertThat(submitted.completedAt()).isNotNull();
        assertThat(adjustments()).isEmpty();
        assertThat(status(box)).isEqualTo("IN_STOCK");
        assertThat(onHand(box)).isEqualTo(CARTON_FACTOR);
    }

    @Test
    @DisplayName("a carton not found is one whole-carton COUNT_VARIANCE; approved, it is LOST with nothing on hand")
    void notFoundIsOneCartonAdjustmentAndLost() {
        UUID kept = carton(BIN, "K");
        UUID gone = carton(BIN, "G");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), cartonLine(planned, kept).id(), true, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, gone).id(), false, counter, false);

        CycleCountView submitted = cycleCounts.submit(planned.id(), counter, false);

        assertThat(submitted.lines()).as("blind: the counter does not see the variance")
                .allSatisfy(line -> assertThat(line.adjustments()).isEmpty());
        assertThat(adjustments()).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("quantity_delta", -CARTON_FACTOR).containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("reason_code", "COUNT_VARIANCE").containsEntry("stock_package_id", gone)
                .containsEntry("cycle_count_line_id", cartonLine(planned, gone).id())
                .containsEntry("requested_by", counter));
        CycleCountView books = cycleCounts.get(planned.id(), true);
        assertThat(cartonLine(books, gone).found()).isFalse();
        assertThat(cartonLine(books, gone).booked()).isTrue();
        assertThat(cartonLine(books, gone).adjustments()).singleElement()
                .satisfies(a -> assertThat(a.quantityDelta()).isEqualTo(-CARTON_FACTOR));
        assertThat(cartonLine(books, kept).adjustments()).isEmpty();
        assertThat(jdbc.queryForList("select counted_qty from inventory.cycle_count_line where id = ?", Integer.class,
                cartonLine(books, gone).id())).as("units are recorded internally, still whole").containsExactly(0);

        operations.approve((UUID) adjustments().get(0).get("id"), manager);

        assertThat(status(gone)).isEqualTo("LOST");
        assertThat(onHand(gone)).isZero();
        assertThat(status(kept)).isEqualTo("IN_STOCK");
        assertThat(onHand(kept)).isEqualTo(CARTON_FACTOR);
    }

    @Test
    @DisplayName("each differing line is one adjustment of its own carton, however many cartons the bin holds")
    void oneAdjustmentPerDifferingLine() {
        UUID a = carton(BIN, "A");
        UUID b = carton(BIN, "B");
        UUID c = carton(BIN2, "C");
        CycleCountView planned = planShelfA01().count();
        cycleCounts.count(planned.id(), cartonLine(planned, a).id(), false, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, b).id(), false, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, c).id(), true, counter, false);

        cycleCounts.submit(planned.id(), counter, false);

        assertThat(adjustments()).extracting(m -> m.get("stock_package_id"), m -> m.get("quantity_delta"))
                .containsExactlyInAnyOrder(tuple(a, -CARTON_FACTOR), tuple(b, -CARTON_FACTOR));
    }

    @Test
    @DisplayName("submitting twice returns the same count and asks for the adjustments once")
    void submitIsIdempotent() {
        carton(BIN, "A");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), planned.lines().get(0).id(), false, counter, false);

        CycleCountView first = cycleCounts.submit(planned.id(), counter, true);
        CycleCountView second = cycleCounts.submit(planned.id(), counter, true);

        assertThat(second.status()).isEqualTo(first.status()).isEqualTo("COMPLETED");
        assertThat(second.number()).isEqualTo(first.number());
        assertThat(second.lines().get(0).adjustments()).isEqualTo(first.lines().get(0).adjustments()).hasSize(1);
        assertThat(adjustments()).as("asked once").hasSize(1);
    }

    @Test
    @DisplayName("submit needs every line counted")
    void submitNeedsEveryLineCounted() {
        UUID a = carton(BIN, "A");
        carton(BIN, "B");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), cartonLine(planned, a).id(), true, counter, false);

        assertThat(code(catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_LINE_NOT_COUNTED);
        assertThat(cycleCounts.get(planned.id(), true).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("scanning the same line again overwrites the answer; the books are read at that moment (BR-09)")
    void countingAgainOverwritesAndReadsTheBooksNow() {
        UUID box = carton(BIN, "A");
        CycleCountView planned = planBin(BIN).count();
        UUID line = planned.lines().get(0).id();
        cycleCounts.count(planned.id(), line, false, counter, false);
        assertThat(cartonLine(cycleCounts.get(planned.id(), true), box).found()).isFalse();

        cycleCounts.count(planned.id(), line, true, counter, false);

        assertThat(cartonLine(cycleCounts.get(planned.id(), true), box).found()).isTrue();
        // The carton is lost between planning and counting: the books no longer have it, and the counter finds it.
        UUID lostBefore = carton(BIN2, "B");
        CycleCountView second = planBin(BIN2).count();
        loseWhole(lostBefore);
        cycleCounts.count(second.id(), second.lines().get(0).id(), true, counter, false);
        CycleCountView books = cycleCounts.get(second.id(), true);
        assertThat(books.lines().get(0).booked()).as("re-read at counting: not in stock any more").isFalse();
        assertThat(books.lines().get(0).found()).isTrue();
        cycleCounts.submit(second.id(), counter, false);
        assertThat(adjustments()).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("quantity_delta", CARTON_FACTOR).containsEntry("stock_package_id", lostBefore));
    }

    @Test
    @DisplayName("a carton moved whole is counted where it is, and its variance names the bin it is in at submission")
    void movedCartonFollowsItsLine() {
        UUID box = carton(BIN, "M");
        CycleCountView planned = planShelfA01().count();
        UUID line = cartonLine(planned, box).id();
        moveWhole(box, BIN2);

        CycleCountView counted = cycleCounts.count(planned.id(), line, false, counter, false);

        assertThat(cartonLine(counted, box).locationCode()).as("the line follows its carton").isEqualTo(BIN2);
        cycleCounts.submit(planned.id(), counter, false);

        assertThat(adjustments()).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("location_code", BIN2).containsEntry("stock_package_id", box)
                .containsEntry("quantity_delta", -CARTON_FACTOR).containsEntry("cycle_count_line_id", line));
        assertThat(cartonLine(cycleCounts.get(planned.id(), true), box).locationCode()).isEqualTo(BIN2);
    }

    @Test
    @DisplayName("a carton emptied after planning cannot be found on the shelf: CONFLICT, nothing recorded")
    void aConsumedCartonIsNotFoundOnTheShelf() {
        UUID box = carton(BIN, "E");
        CycleCountView planned = planBin(BIN).count();
        UUID line = cartonLine(planned, box).id();
        consumeWhole(box);

        assertThat(code(catchThrowable(() -> cycleCounts.count(planned.id(), line, true, counter, false))))
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(cartonLine(cycleCounts.get(planned.id(), true), box).found()).as("nothing recorded").isNull();
    }

    // --- BR-09: a carton that changed after its line was counted

    @Test
    @DisplayName("BR-09: a carton moved out of the line's bin after counting is CYCLE_COUNT_STALE, naming line and LPN")
    void movedAfterCountingIsStale() {
        UUID box = carton(BIN, "S");
        CycleCountView planned = planBin(BIN).count();
        UUID line = cartonLine(planned, box).id();
        cycleCounts.count(planned.id(), line, false, counter, false);
        moveWhole(box, BIN2);

        Throwable stale = catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false));

        assertThat(code(stale)).isEqualTo(ErrorCode.CYCLE_COUNT_STALE);
        assertThat(stale).hasMessageContaining(line.toString()).hasMessageContaining(lpn("S"));
        assertThat(adjustments()).isEmpty();
        assertThat(cycleCounts.get(planned.id(), true).status()).as("the count stays open").isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("BR-09: a carton emptied after counting is CYCLE_COUNT_STALE; so is one lost since")
    void emptiedOrLostAfterCountingIsStale() {
        UUID emptied = carton(BIN, "E");
        CycleCountView first = planBin(BIN).count();
        UUID line = cartonLine(first, emptied).id();
        cycleCounts.count(first.id(), line, false, counter, false);
        consumeWhole(emptied);

        Throwable stale = catchThrowable(() -> cycleCounts.submit(first.id(), counter, false));

        assertThat(code(stale)).isEqualTo(ErrorCode.CYCLE_COUNT_STALE);
        assertThat(stale).hasMessageContaining(line.toString()).hasMessageContaining(lpn("E"));
        assertThat(adjustments()).isEmpty();

        UUID lost = carton(BIN2, "L");
        CycleCountView second = planBin(BIN2).count();
        cycleCounts.count(second.id(), cartonLine(second, lost).id(), false, counter, false);
        loseWhole(lost);

        assertThat(code(catchThrowable(() -> cycleCounts.submit(second.id(), counter, false))))
                .as("an approved write-off already took it out of the books: counting it missing is stale")
                .isEqualTo(ErrorCode.CYCLE_COUNT_STALE);
        assertThat(adjustments()).isEmpty();
    }

    @Test
    @DisplayName("BR-09: a LOST carton found by the count but restocked since is CYCLE_COUNT_STALE (no double surplus)")
    void foundSinceIsStale() {
        UUID box = carton(BIN, "F");
        loseWhole(box);
        carton(BIN2, "G");
        CycleCountView planned = planBin(BIN2).count();
        cycleCounts.addLine(planned.id(), new AddCountLineCommand(BIN, lpn("F"), counter, false));
        findWhole(box);

        assertThat(code(catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_STALE);
        assertThat(adjustments()).isEmpty();
    }

    @Test
    @DisplayName("a line that differs from nothing is not judged: a carton emptied after being found present is no variance")
    void aLineWithoutVarianceIsNotChecked() {
        UUID box = carton(BIN, "P");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), cartonLine(planned, box).id(), true, counter, false);
        consumeWhole(box);

        assertThat(cycleCounts.submit(planned.id(), counter, false).status()).isEqualTo("COMPLETED");
        assertThat(adjustments()).isEmpty();
    }

    @Test
    @DisplayName("a stale line rolls the whole submission back: the shortage of another line is not asked")
    void staleRollsBackTheWholeSubmission() {
        // BIN (HCM-A01-1-B) sorts before BIN2 (HCM-A01-2-A): its shortage is asked - and flushed - first, so the
        // refusal of the second line has something to roll back.
        UUID first = carton(BIN, "A");
        UUID second = carton(BIN2, "B");
        CycleCountView planned = planShelfA01().count();
        cycleCounts.count(planned.id(), cartonLine(planned, first).id(), false, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, second).id(), false, counter, false);
        consumeWhole(second);

        assertThat(code(catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_STALE);

        assertThat(cycleCounts.get(planned.id(), true).status()).isEqualTo("IN_PROGRESS");
        assertThat(adjustments()).as("the first line's shortage, asked before the refusal, is rolled back").isEmpty();
    }

    @Test
    @DisplayName("submitting a cancelled count is CYCLE_COUNT_NOT_OPEN, even with a carton on it emptied since")
    void submitOfACancelledCountIsNotOpen() {
        UUID box = carton(BIN, "C");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), cartonLine(planned, box).id(), false, counter, false);
        cycleCounts.cancel(planned.id(), "Not needed", "test");
        consumeWhole(box);

        assertThat(code(catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_NOT_OPEN);
    }

    // --- adding a scanned carton (20 §4.2 b5)

    @Test
    @DisplayName("a LOST carton scanned by a later count is added by its LPN and, once approved, back in stock whole")
    void addLineFindsALostCarton() {
        UUID box = carton(BIN, "L");
        // A first count does not find it, and the manager approves the loss.
        CycleCountView first = planBin(BIN).count();
        cycleCounts.count(first.id(), cartonLine(first, box).id(), false, counter, false);
        cycleCounts.submit(first.id(), counter, false);
        operations.approve((UUID) adjustments().get(0).get("id"), manager);
        assertThat(status(box)).isEqualTo("LOST");

        // A count of the next bin: the carton turns up in BIN2, away from its recorded bin - a move, not a count.
        carton(BIN2, "O");
        CycleCountView second = planBin(BIN2).count();
        cycleCounts.count(second.id(), second.lines().get(0).id(), true, counter, false);
        Throwable elsewhere = catchThrowable(() -> cycleCounts.addLine(second.id(),
                new AddCountLineCommand(BIN2, lpn("L"), counter, false)));
        assertThat(code(elsewhere)).isEqualTo(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION);
        assertThat(elsewhere).hasMessageContaining(BIN);
        // Brought back to its bin, it is scanned there - the LPN and code typed in lower case.
        CycleCountView added = cycleCounts.addLine(second.id(), new AddCountLineCommand(BIN.toLowerCase(Locale.ROOT),
                lpn("L").toLowerCase(Locale.ROOT), counter, false));

        assertThat(cartonLine(added, box)).satisfies(line -> {
            assertThat(line.locationCode()).isEqualTo(BIN);
            assertThat(line.lpn()).isEqualTo(lpn("L"));
            assertThat(line.lotNumber()).isEqualTo(lot);
            assertThat(line.found()).as("what was scanned is the counter's to see").isTrue();
            assertThat(line.booked()).as("hidden from a blind counter").isNull();
        });
        CycleCountView books = cycleCounts.get(second.id(), true);
        assertThat(cartonLine(books, box).booked()).as("a LOST carton is not in the books").isFalse();
        cycleCounts.submit(second.id(), counter, false);
        List<Map<String, Object>> found = adjustments().stream()
                .filter(a -> box.equals(a.get("stock_package_id")) && ((Integer) a.get("quantity_delta")) > 0).toList();
        assertThat(found).singleElement().satisfies(a -> assertThat(a).containsEntry("quantity_delta", CARTON_FACTOR));
        operations.approve((UUID) found.get(0).get("id"), manager);

        assertThat(status(box)).isEqualTo("IN_STOCK");
        assertThat(onHand(box)).isEqualTo(CARTON_FACTOR);
        assertThat(atp()).as("the carton found again is allocatable, with the other bin's")
                .isEqualTo(2 * CARTON_FACTOR);
    }

    @Test
    @DisplayName("a carton in stock the plan missed is added present: no variance")
    void addLineOfACartonInStockIsNoVariance() {
        carton(BIN, "P");
        UUID missed = carton(BIN2, "M");
        CycleCountView planned = planBin(BIN).count();

        CycleCountView added = cycleCounts.addLine(planned.id(), new AddCountLineCommand(BIN2, lpn("M"), manager,
                true));

        assertThat(cartonLine(added, missed).booked()).isTrue();
        assertThat(cartonLine(added, missed).found()).isTrue();
        CycleCountView rest = cycleCounts.get(planned.id(), true);
        for (CycleCountView.Line line : rest.lines()) {
            if (line.found() == null) {
                cycleCounts.count(planned.id(), line.id(), true, manager, true);
            }
        }
        cycleCounts.submit(planned.id(), manager, true);
        assertThat(adjustments()).isEmpty();
    }

    @Test
    @DisplayName("addLine refuses an unknown LPN, a carton recorded elsewhere, an emptied carton, no LPN, a bad bin")
    void addLineRefusals() {
        carton(BIN, "A");
        carton(BIN2, "W");
        UUID emptied = carton(BIN3, "X");
        consumeWhole(emptied);
        CycleCountView planned = planBin(BIN).count();

        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, "LPN-NOPE-0001", counter, false)))))
                .as("no such carton").isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, lpn("W"), counter, false)))))
                .as("scanned in " + BIN + ", recorded at " + BIN2).isEqualTo(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN3, lpn("X"), counter, false)))))
                .as("a CONSUMED carton is not on a shelf to count").isEqualTo(ErrorCode.CONFLICT);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, null, counter, false)))))
                .as("a carton is added by its LPN").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(null, lpn("W"), counter, false)))))
                .as("with the bin it was scanned in").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand("NOT-A-BIN", lpn("W"), counter, false)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(cycleCounts.get(planned.id(), true).lines()).hasSize(1);
    }

    @Test
    @DisplayName("a carton already on the count, or on another open count, cannot be added")
    void addLineRefusesABusyCarton() {
        carton(BIN, "A");
        UUID other = carton(BIN2, "B");
        CycleCountView planned = planBin(BIN).count();
        CycleCountView elsewhere = planBin(BIN2).count();

        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, lpn("A"), counter, false)))))
                .as("already a line of this count").isEqualTo(ErrorCode.CYCLE_COUNT_SLOT_BUSY);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN2, lpn("B"), counter, false)))))
                .as("a line of another open count (BR-10)").isEqualTo(ErrorCode.CYCLE_COUNT_SLOT_BUSY);
        assertThat(elsewhere.lines()).extracting(CycleCountView.Line::stockPackageId).containsExactly(other);
    }

    // ------------------------------------------------------------------ the SHORT_PICK freeze (D7)

    @Test
    @DisplayName("a short pick's freeze holds after submission while the variance waits, and lifts once it is approved")
    void shortPickFreezeLastsUntilTheVarianceIsDecided() {
        UUID box = carton(BIN, "F");
        carton(BIN2, "G");
        assertThat(atp()).isEqualTo(2 * CARTON_FACTOR);
        CycleCount recount = shortPick(box, BIN);
        assertThat(atp()).as("the carton is in doubt").isEqualTo(CARTON_FACTOR);

        cycleCounts.count(recount.id(), recount.lines().get(0).id(), false, counter, false);
        cycleCounts.submit(recount.id(), counter, false);

        assertThat(atp()).as("still in doubt while the variance waits for approval").isEqualTo(CARTON_FACTOR);
        assertThat(frozen()).isNotEmpty();

        operations.approve((UUID) adjustments().get(0).get("id"), manager);

        assertThat(frozen()).isEmpty();
        assertThat(atp()).as("the carton is lost").isEqualTo(CARTON_FACTOR);
        assertThat(status(box)).isEqualTo("LOST");
    }

    @Test
    @DisplayName("a short pick on a carton freezes that carton and no other of the same bin, SKU and lot")
    void shortPickFreezesOnlyThatCarton() {
        carton(BIN, "A");
        UUID box = carton(BIN, "B");
        UUID boxRow = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                box);

        shortPick(box, BIN);

        assertThat(frozen()).singleElement().satisfies(f -> {
            assertThat(f.stockItemId()).isEqualTo(boxRow);
            assertThat(f.free()).isEqualTo(CARTON_FACTOR);
        });
        assertThat(atp()).as("the other carton stays sellable").isEqualTo(CARTON_FACTOR);
    }

    @Test
    @DisplayName("BR-11: a count's pending shortage keeps the carton out of ATP until it is decided")
    void pendingShortageFreezesTheCarton() {
        UUID box = carton(BIN, "A");
        carton(BIN2, "B");
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), planned.lines().get(0).id(), false, counter, false);
        cycleCounts.submit(planned.id(), counter, false);

        assertThat(atp()).isEqualTo(CARTON_FACTOR);
        assertThat(frozen()).singleElement().satisfies(f -> assertThat(f.free()).isEqualTo(CARTON_FACTOR));

        operations.reject((UUID) adjustments().get(0).get("id"), manager, "Doubtful");

        assertThat(frozen()).as("decided, the carton is the books' again").isEmpty();
        assertThat(atp()).isEqualTo(2 * CARTON_FACTOR);
        assertThat(status(box)).isEqualTo("IN_STOCK");
    }

    // ------------------------------------------------------------------ recount

    @Test
    @DisplayName("a rejected variance is recounted on a new blind MANUAL count; the old line is flagged")
    void recountOpensANewBlindCount() {
        UUID doubted = carton(BIN, "A");
        UUID fine = carton(BIN2, "B");
        CycleCountView planned = planShelfA01().count();
        UUID doubtedLine = cartonLine(planned, doubted).id();
        cycleCounts.count(planned.id(), doubtedLine, false, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, fine).id(), true, counter, false);
        cycleCounts.submit(planned.id(), counter, false);
        UUID adjustment = (UUID) adjustments().get(0).get("id");

        // While the variance waits for approval, its carton belongs to it: a new plan skips it.
        PlannedCount meanwhile = planShelfA01();
        assertThat(meanwhile.count().lines()).extracting(CycleCountView.Line::stockPackageId).containsExactly(fine);
        assertThat(meanwhile.skipped()).singleElement()
                .satisfies(slot -> assertThat(slot.countNumber()).isEqualTo(planned.number()));
        cycleCounts.cancel(meanwhile.count().id(), "Not needed", "test");
        assertThat(code(catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubtedLine), "Doubtful",
                manager)))).as("not rejected yet").isEqualTo(ErrorCode.CONFLICT);

        operations.reject(adjustment, manager, "Doubtful figure");
        CycleCountView recount = cycleCounts.recount(planned.id(), List.of(doubtedLine), "Check behind the boxes",
                manager);

        assertThat(recount.id()).isNotEqualTo(planned.id());
        assertThat(recount.triggerType()).isEqualTo("MANUAL");
        assertThat(recount.status()).isEqualTo("PLANNED");
        assertThat(recount.blind()).isTrue();
        assertThat(recount.note()).isEqualTo("Recount of " + planned.number() + ": Check behind the boxes");
        assertThat(recount.lines()).singleElement().satisfies(line -> {
            assertThat(line.locationCode()).isEqualTo(BIN);
            assertThat(line.sku()).isEqualTo(sku);
            assertThat(line.lotNumber()).isEqualTo(lot);
            assertThat(line.stockPackageId()).isEqualTo(doubted);
        });
        CycleCountView old = cycleCounts.get(planned.id(), true);
        assertThat(cartonLine(old, doubted).recountRequired()).isTrue();
        assertThat(cartonLine(old, fine).recountRequired()).isFalse();
        assertThat(code(catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubtedLine), null, manager))))
                .as("sent for recount once").isEqualTo(ErrorCode.CONFLICT);

        // The recount settles the carton: found this time, so no variance.
        cycleCounts.count(recount.id(), recount.lines().get(0).id(), true, counter, false);
        cycleCounts.submit(recount.id(), counter, false);
        assertThat(adjustments()).as("only the rejected one").hasSize(1);
        assertThat(code(catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubtedLine), null, manager))))
                .as("after the recount completed").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("a carton emptied after its variance was rejected is CYCLE_COUNT_STALE at recount; nothing flagged")
    void recountOfAnEmptiedCartonIsStale() {
        UUID box = carton(BIN2, "R");
        CycleCountView planned = planBin(BIN2).count();
        UUID doubted = cartonLine(planned, box).id();
        cycleCounts.count(planned.id(), doubted, false, counter, false);
        cycleCounts.submit(planned.id(), counter, false);
        operations.reject((UUID) adjustments().get(0).get("id"), manager, "Doubtful");
        consumeWhole(box);

        Throwable recount = catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubted), null, manager));

        assertThat(code(recount)).isEqualTo(ErrorCode.CYCLE_COUNT_STALE);
        assertThat(recount).hasMessageContaining(doubted.toString());
        assertThat(cartonLine(cycleCounts.get(planned.id(), true), box).recountRequired()).as("nothing flagged")
                .isFalse();
    }

    @Test
    @DisplayName("a rejected short-pick shortage is recounted as SHORT_PICK, so the carton stays frozen while counted")
    void shortPickRecountKeepsTheFreeze() {
        UUID box = carton(BIN, "A");
        CycleCount shortPick = shortPick(box, BIN);
        UUID doubted = shortPick.lines().get(0).id();
        cycleCounts.count(shortPick.id(), doubted, false, counter, false);
        cycleCounts.submit(shortPick.id(), counter, false);
        operations.reject((UUID) adjustments().get(0).get("id"), manager, "Doubtful figure");
        assertThat(frozen()).as("the rejection decided the shortage").isEmpty();

        CycleCountView recount = cycleCounts.recount(shortPick.id(), List.of(doubted), null, manager);

        assertThat(recount.triggerType()).isEqualTo("SHORT_PICK");
        assertThat(frozen()).as("in doubt again while it is recounted").isNotEmpty();
        assertThat(atp()).isZero();
    }

    // ------------------------------------------------------------------ who counts

    @Test
    @DisplayName("a count goes only to an active warehouse staff member or manager of its warehouse")
    void assigneeMustBeWarehouseStaff() {
        carton(BIN, "A");
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        UUID inspector = staff("INVENTORY_PLANNER", WAREHOUSE_HCM);
        UUID unassigned = staff("WAREHOUSE_STAFF");

        assertThat(cycleCounts.assignees(WAREHOUSE_HCM)).extracting(AssignableUser::userId)
                .contains(clerk).doesNotContain(inspector, unassigned);
        assertThat(code(catchThrowable(() -> plan(inspector, List.of(), List.of(), List.of(BIN), List.of(sku)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> plan(unassigned, List.of(), List.of(), List.of(BIN), List.of(sku)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        CycleCountView planned = plan(clerk, List.of(), List.of(), List.of(BIN), List.of(sku)).count();

        assertThat(planned.assignedUserId()).isEqualTo(clerk);

        // Handed on, the blind count answers as get does: the books to who decides, not to anyone else.
        UUID colleague = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        assertThat(cycleCounts.assign(planned.id(), colleague, true).lines().get(0).booked()).isTrue();
        assertThat(cycleCounts.assign(planned.id(), clerk, false).lines().get(0).booked()).isNull();
    }

    @Test
    @DisplayName("only the assignee counts an assigned count, unless the caller decides counts")
    void onlyTheAssigneeCounts() {
        carton(BIN, "A");
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        UUID colleague = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView planned = plan(clerk, List.of(), List.of(), List.of(BIN), List.of(sku)).count();
        UUID line = planned.lines().get(0).id();

        assertThat(code(catchThrowable(() -> cycleCounts.count(planned.id(), line, true, colleague, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_NOT_ASSIGNEE);
        assertThat(cycleCounts.count(planned.id(), line, false, manager, true).lines().get(0).countedBy())
                .as("who decides counts may count").isEqualTo(manager);
        assertThat(cycleCounts.count(planned.id(), line, true, clerk, false).lines().get(0).countedBy())
                .isEqualTo(clerk);
        assertThat(code(catchThrowable(() -> cycleCounts.assign(planned.id(), colleague, true))))
                .as("a count being counted keeps its counter").isEqualTo(ErrorCode.CONFLICT);
    }
}
