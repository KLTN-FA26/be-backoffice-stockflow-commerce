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
 * SCRUM-146 end to end below HTTP, on the demo warehouse {@code HCM} and a real PostgreSQL: a count is planned over a
 * scope, counted blind against the books as they stand at counting, and submitted into pending
 * {@code COUNT_VARIANCE} adjustments that a second person approves; a doubted variance is rejected and recounted.
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

    private UUID loose(String location, int onHand) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at, on_hand,
                    reserved, status, version, created_at) values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                id, sku, location, lot, Date.valueOf(LocalDate.now().plusYears(1)), Timestamp.from(EARLY), onHand));
        return id;
    }

    /** A carton at {@code location} with {@code quantity} counted into it and one row holding all of it. */
    private UUID carton(String location, String name, int quantity, boolean unopened) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status, unopened) values (?, ?, ?, ?, ?, ?, ?, 'IN_STOCK', ?)""",
                    id, lpn(name), item, cartonUnit, CARTON_FACTOR, quantity, location, unopened);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, lot_number, expiry_date, received_at,
                        on_hand, reserved, status, version, created_at, stock_package_id)
                    values (?, ?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), sku, location, lot, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(EARLY), quantity, id);
        });
        return id;
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

    /** A SHORT_PICK recount as a pick opens it ({@code PickConsumption.requestRecount}), on one slot. */
    private CycleCount shortPick(CountSlot slot, int expected) {
        return tx.execute(s -> {
            LocalDate day = BusinessCalendar.date(Instant.now());
            return repository.save(CycleCount.shortPick(repository.nextNumber(day), WAREHOUSE_HCM, slot, expected,
                    "Short pick: found 1", day));
        });
    }

    private PlannedCount plan(List<UUID> zones, List<String> shelves, List<String> codes, List<String> skus) {
        return plan(null, zones, shelves, codes, skus);
    }

    private PlannedCount plan(UUID assignee, List<UUID> zones, List<String> shelves, List<String> codes,
                              List<String> skus) {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, assignee, true, "Fixture count",
                zones, shelves, codes, skus));
    }

    /** Shelf A01, this test's SKU only. */
    private PlannedCount planShelfA01() {
        return plan(List.of(), List.of("A01"), List.of(), List.of(sku));
    }

    private PlannedCount planBin(String location) {
        return plan(List.of(), List.of(), List.of(location), List.of(sku));
    }

    private CountSlot looseSlot(String location) {
        return CountSlot.loose(new LocationId(location), new Sku(sku), lot);
    }

    private static CycleCountView.Line looseLine(CycleCountView view, String location) {
        return view.lines().stream().filter(l -> l.locationCode().equals(location) && l.stockPackageId() == null)
                .findFirst().orElseThrow();
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
                select id, quantity_delta, status, reason_code, cycle_count_line_id, stock_package_id, requested_by
                  from inventory.stock_adjustment where sku = ? order by created_at, quantity_delta""", sku);
    }

    private int onHand(UUID row) {
        return jdbc.queryForObject("select on_hand from inventory.stock_item where id = ?", Integer.class, row);
    }

    private Map<String, Object> cartonState(UUID id) {
        return jdbc.queryForMap("""
                select p.status,
                       (select coalesce(sum(s.on_hand), 0) from inventory.stock_item s where s.stock_package_id = p.id) as held
                  from inventory.stock_package p where p.id = ?""", id);
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

    // ------------------------------------------------------------------ planning (SCRUM-162)

    @Test
    @DisplayName("a shelf plan has one line per stocked loose slot and per carton in stock under that shelf, MANUAL")
    void planByShelfGeneratesLooseAndCartonLines() {
        loose(BIN, 5);
        loose(BIN2, 3);
        loose("HCM-A01-1-A", 0);
        loose(A02_BIN, 4);
        UUID box = carton(BIN, "P", 20, true);

        PlannedCount planned = planShelfA01();

        CycleCountView count = planned.count();
        assertThat(count.number()).startsWith("CC-");
        assertThat(count.triggerType()).isEqualTo("MANUAL");
        assertThat(count.status()).isEqualTo("PLANNED");
        assertThat(count.blind()).isTrue();
        assertThat(count.warehouseId()).isEqualTo(WAREHOUSE_HCM);
        assertThat(planned.skipped()).isEmpty();
        assertThat(count.lines()).as("an empty slot and shelf A02 are not counted")
                .extracting(CycleCountView.Line::locationCode, CycleCountView.Line::sku,
                        CycleCountView.Line::lotNumber, CycleCountView.Line::stockPackageId)
                .containsExactlyInAnyOrder(tuple(BIN, sku, lot, null), tuple(BIN2, sku, lot, null),
                        tuple(BIN, sku, lot, box));
        assertThat(count.lines()).as("planning does not make the planner one who decides")
                .allSatisfy(line -> assertThat(line.expectedQty()).isNull());

        CycleCountView books = cycleCounts.get(count.id(), true);
        assertThat(looseLine(books, BIN).expectedQty()).isEqualTo(5);
        assertThat(looseLine(books, BIN2).expectedQty()).isEqualTo(3);
        assertThat(cartonLine(books, box).expectedQty()).isEqualTo(20);
        assertThat(cartonLine(books, box).lpn()).isEqualTo(lpn("P"));
        assertThat(books.note()).isEqualTo("Fixture count");
    }

    @Test
    @DisplayName("a zone plan selects exactly what a plan of its shelves does")
    void planByZoneEqualsPlanByItsShelves() {
        loose(BIN, 5);
        loose(A02_BIN, 4);
        loose(B01_BIN, 2);
        carton(BIN2, "Z", 10, true);

        PlannedCount byZone = plan(List.of(DemoData.ZONE_HCM_A), List.of(), List.of(), List.of(sku));
        Set<String> zoneSlots = slots(byZone.count());
        cycleCounts.cancel(byZone.count().id(), "Compared with the shelves", "test");
        PlannedCount byShelves = plan(List.of(), List.of("A01", "A02"), List.of(), List.of(sku));

        assertThat(zoneSlots).hasSize(3).noneMatch(slot -> slot.startsWith(B01_BIN));
        assertThat(slots(byShelves.count())).isEqualTo(zoneSlots);
    }

    @Test
    @DisplayName("a SKU alone means its bins: stock in a receiving area is not counted")
    void planBySkuOnlyCoversBinsNotAreas() {
        loose(BIN, 5);
        loose(AREA, 7);

        PlannedCount planned = plan(List.of(), List.of(), List.of(), List.of(sku));

        assertThat(planned.count().lines()).extracting(CycleCountView.Line::locationCode).containsExactly(BIN);
    }

    @Test
    @DisplayName("a slot on an open SHORT_PICK recount is skipped and named with the recount's number")
    void planSkipsBusySlots() {
        loose(BIN, 5);
        loose(BIN2, 3);
        CycleCount recount = shortPick(looseSlot(BIN), 5);

        PlannedCount planned = planShelfA01();

        assertThat(planned.count().lines()).extracting(CycleCountView.Line::locationCode).containsExactly(BIN2);
        assertThat(planned.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.locationCode()).isEqualTo(BIN);
            assertThat(skipped.sku()).isEqualTo(sku);
            assertThat(skipped.lotNumber()).isEqualTo(lot);
            assertThat(skipped.lpn()).isNull();
            assertThat(skipped.countNumber()).isEqualTo(recount.number());
        });
    }

    @Test
    @DisplayName("a scope with no stock is CYCLE_COUNT_EMPTY_SCOPE, one above the cap CYCLE_COUNT_TOO_LARGE")
    void planRefusesEmptyAndTooLarge() {
        assertThat(code(catchThrowable(this::planShelfA01))).isEqualTo(ErrorCode.CYCLE_COUNT_EMPTY_SCOPE);
        assertThat(code(catchThrowable(() -> plan(List.of(), List.of(), List.of(), List.of()))))
                .as("no criterion at all").isEqualTo(ErrorCode.VALIDATION_FAILED);
        loose(BIN, 5);
        loose(BIN2, 3);

        Object service = AopTestUtils.getUltimateTargetObject(cycleCounts);
        Object cap = ReflectionTestUtils.getField(service, "maxLines");
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
    @DisplayName("plan, count against the books re-read at counting, submit, approve: on_hand is what was counted")
    void fullLooseCycle() {
        UUID row = loose(BIN, 10);
        CycleCountView planned = planBin(BIN).count();
        UUID line = planned.lines().get(0).id();
        // A receipt after planning: the books say 12 when the shelf is counted.
        tx.executeWithoutResult(s -> jdbc.update("update inventory.stock_item set on_hand = 12 where id = ?", row));

        CycleCountView counted = cycleCounts.count(planned.id(), line, 11, null, counter, false);

        assertThat(counted.status()).isEqualTo("IN_PROGRESS");
        assertThat(counted.startedAt()).isNotNull();
        assertThat(counted.lines().get(0).countedQty()).isEqualTo(11);
        assertThat(counted.lines().get(0).countedBy()).isEqualTo(counter);
        assertThat(counted.lines().get(0).expectedQty()).as("blind").isNull();

        CycleCountView submitted = cycleCounts.submit(planned.id(), counter, false);

        assertThat(submitted.status()).isEqualTo("COMPLETED");
        assertThat(submitted.completedAt()).isNotNull();
        assertThat(submitted.lines().get(0).adjustments()).as("blind").isEmpty();
        Map<String, Object> asked = adjustments().get(0);
        assertThat(adjustments()).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("quantity_delta", -1).containsEntry("status", "PENDING_APPROVAL")
                    .containsEntry("reason_code", "COUNT_VARIANCE").containsEntry("cycle_count_line_id", line)
                    .containsEntry("requested_by", counter);
            assertThat(a.get("stock_package_id")).isNull();
        });
        CycleCountView books = cycleCounts.get(planned.id(), true);
        assertThat(books.lines().get(0).expectedQty()).as("re-read at counting, not the 10 planned").isEqualTo(12);
        assertThat(books.lines().get(0).adjustments()).singleElement()
                .satisfies(a -> assertThat(a.quantityDelta()).isEqualTo(-1));

        operations.approve((UUID) asked.get("id"), manager);

        assertThat(onHand(row)).isEqualTo(11);
    }

    @Test
    @DisplayName("submitting twice returns the same count and asks for the adjustments once")
    void submitIsIdempotent() {
        loose(BIN, 10);
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), planned.lines().get(0).id(), 8, null, counter, false);

        CycleCountView first = cycleCounts.submit(planned.id(), counter, true);
        CycleCountView second = cycleCounts.submit(planned.id(), counter, true);

        assertThat(second.status()).isEqualTo(first.status()).isEqualTo("COMPLETED");
        assertThat(second.number()).isEqualTo(first.number());
        assertThat(second.lines()).extracting(CycleCountView.Line::id, CycleCountView.Line::countedQty)
                .containsExactlyElementsOf(first.lines().stream().map(l -> tuple(l.id(), l.countedQty())).toList());
        assertThat(second.lines().get(0).adjustments()).isEqualTo(first.lines().get(0).adjustments()).hasSize(1);
        assertThat(adjustments()).as("asked once").hasSize(1);
    }

    @Test
    @DisplayName("a shortage beyond what the slot holds now is CYCLE_COUNT_STALE; the submission rolls back whole")
    void shortageBeyondCurrentStockIsStale() {
        UUID row = loose(BIN, 5);
        loose(BIN2, 3);
        CycleCountView planned = planShelfA01().count();
        cycleCounts.count(planned.id(), looseLine(planned, BIN).id(), 0, null, counter, false);
        cycleCounts.count(planned.id(), looseLine(planned, BIN2).id(), 4, null, counter, false);
        // Three units leave the bin after it was counted (a pick): the -5 no longer fits in what is there.
        tx.executeWithoutResult(s -> jdbc.update("update inventory.stock_item set on_hand = 2 where id = ?", row));

        assertThat(code(catchThrowable(() -> cycleCounts.submit(planned.id(), counter, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_STALE);

        assertThat(cycleCounts.get(planned.id(), true).status()).isEqualTo("IN_PROGRESS");
        assertThat(adjustments()).as("not even the other line's surplus").isEmpty();
    }

    @Test
    @DisplayName("a sealed carton scanned present has no variance; one not found is one adjustment and goes LOST")
    void cartonPresentAndMissing() {
        UUID kept = carton(BIN, "K", 20, true);
        UUID gone = carton(BIN, "G", 30, true);
        CycleCountView planned = planBin(BIN).count();

        cycleCounts.count(planned.id(), cartonLine(planned, kept).id(), null, true, counter, false);
        cycleCounts.count(planned.id(), cartonLine(planned, gone).id(), null, false, counter, false);
        cycleCounts.submit(planned.id(), counter, false);

        CycleCountView books = cycleCounts.get(planned.id(), true);
        assertThat(cartonLine(books, kept).countedQty()).isEqualTo(20);
        assertThat(cartonLine(books, kept).adjustments()).isEmpty();
        assertThat(adjustments()).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("stock_package_id", gone).containsEntry("quantity_delta", -30)
                    .containsEntry("cycle_count_line_id", cartonLine(books, gone).id());
        });

        operations.approve((UUID) adjustments().get(0).get("id"), manager);

        assertThat(cartonState(gone)).containsEntry("status", "LOST").containsEntry("held", 0L);
        assertThat(cartonState(kept)).containsEntry("status", "IN_STOCK").containsEntry("held", 20L);
    }

    @Test
    @DisplayName("a carton counted above what it was received with is STOCK_PACKAGE_OVERFILLED; present needs a sealed one")
    void cartonAboveReceivedIsRefused() {
        UUID box = carton(BIN, "O", 20, false);
        CycleCountView planned = planBin(BIN).count();
        UUID line = cartonLine(planned, box).id();

        assertThat(code(catchThrowable(() -> cycleCounts.count(planned.id(), line, 25, null, counter, false))))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_OVERFILLED);
        assertThat(code(catchThrowable(() -> cycleCounts.count(planned.id(), line, null, true, counter, false))))
                .as("an opened carton is counted in units").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(cycleCounts.count(planned.id(), line, 18, null, counter, false).lines().get(0).countedQty())
                .isEqualTo(18);
    }

    @Test
    @DisplayName("a LOST carton scanned by a later count is added by its LPN and, once approved, back in stock")
    void addLineFindsALostCarton() {
        UUID box = carton(BIN, "L", 20, true);
        loose(BIN2, 4);
        // A first count does not find it, and the manager approves the loss.
        CycleCountView first = planBin(BIN).count();
        cycleCounts.count(first.id(), cartonLine(first, box).id(), null, false, counter, false);
        cycleCounts.submit(first.id(), counter, false);
        operations.approve((UUID) adjustments().get(0).get("id"), manager);
        assertThat(cartonState(box)).containsEntry("status", "LOST");

        // A count of the next bin finds it - the LPN typed in lower case.
        CycleCountView second = planBin(BIN2).count();
        cycleCounts.count(second.id(), looseLine(second, BIN2).id(), 4, null, counter, false);
        CycleCountView added = cycleCounts.addLine(second.id(), new AddCountLineCommand(null, null, null,
                lpn("L").toLowerCase(Locale.ROOT), 20, counter, false));

        assertThat(cartonLine(added, box)).satisfies(line -> {
            assertThat(line.locationCode()).isEqualTo(BIN);
            assertThat(line.lpn()).isEqualTo(lpn("L"));
            assertThat(line.lotNumber()).isEqualTo(lot);
            assertThat(line.countedQty()).isEqualTo(20);
        });
        cycleCounts.submit(second.id(), counter, false);
        List<Map<String, Object>> found = adjustments().stream()
                .filter(a -> box.equals(a.get("stock_package_id")) && ((Integer) a.get("quantity_delta")) > 0).toList();
        assertThat(found).singleElement().satisfies(a -> assertThat(a).containsEntry("quantity_delta", 20));
        operations.approve((UUID) found.get(0).get("id"), manager);

        assertThat(cartonState(box)).containsEntry("status", "IN_STOCK").containsEntry("held", 20L);
    }

    @Test
    @DisplayName("a found line is refused for a bin with no row of that SKU and lot, and for a carton recorded elsewhere")
    void addLineRefusesUnknownLayerAndWrongLocation() {
        loose(BIN, 5);
        carton(BIN2, "W", 10, true);
        CycleCountView planned = planBin(BIN).count();

        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(A02_BIN, sku, lot, null, 3, counter, false)))))
                .as("no row of it in that bin: a move, not an adjustment").isEqualTo(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, sku, "OTHER-LOT", null, 3, counter, false)))))
                .as("another lot").isEqualTo(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER);
        assertThat(code(catchThrowable(() -> cycleCounts.addLine(planned.id(),
                new AddCountLineCommand(BIN, null, null, lpn("W"), 10, counter, false)))))
                .as("scanned in " + BIN + ", recorded at " + BIN2).isEqualTo(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION);

        assertThat(cycleCounts.get(planned.id(), true).lines()).hasSize(1);
    }

    @Test
    @DisplayName("a found line added to a count already counting survives a reload; codes are upper-cased")
    void addLineToACountAlreadySaved() {
        loose(BIN, 5);
        loose(BIN2, 2);
        CycleCountView planned = planBin(BIN).count();
        cycleCounts.count(planned.id(), planned.lines().get(0).id(), 5, null, counter, false);

        cycleCounts.addLine(planned.id(), new AddCountLineCommand(BIN2.toLowerCase(Locale.ROOT),
                sku.toLowerCase(Locale.ROOT), lot, null, 2, counter, false));

        CycleCountView reloaded = cycleCounts.get(planned.id(), true);
        assertThat(reloaded.status()).isEqualTo("IN_PROGRESS");
        assertThat(reloaded.lines()).hasSize(2);
        assertThat(looseLine(reloaded, BIN2)).satisfies(line -> {
            assertThat(line.sku()).isEqualTo(sku);
            assertThat(line.lotNumber()).isEqualTo(lot);
            assertThat(line.countedQty()).isEqualTo(2);
            assertThat(line.expectedQty()).isEqualTo(2);
            assertThat(line.countedBy()).isEqualTo(counter);
        });
        assertThat(jdbc.queryForObject("select count(*) from inventory.cycle_count_line where cycle_count_id = ?",
                Integer.class, planned.id())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the SHORT_PICK freeze (D7)

    @Test
    @DisplayName("a short pick's freeze holds after submission while the variance waits, and lifts once it is approved")
    void shortPickFreezeLastsUntilTheVarianceIsDecided() {
        loose(BIN, 5);
        assertThat(atp()).isEqualTo(5);
        CycleCount recount = shortPick(looseSlot(BIN), 5);
        assertThat(atp()).as("the bin's free units are in doubt").isZero();

        cycleCounts.count(recount.id(), recount.lines().get(0).id(), 3, null, counter, false);
        cycleCounts.submit(recount.id(), counter, false);

        assertThat(atp()).as("still in doubt while the variance waits for approval").isZero();
        assertThat(frozen()).isNotEmpty();

        operations.approve((UUID) adjustments().get(0).get("id"), manager);

        assertThat(frozen()).isEmpty();
        assertThat(atp()).isEqualTo(3);
    }

    @Test
    @DisplayName("a short pick on a carton row freezes that carton, not the loose units of the same bin, SKU and lot")
    void shortPickOnACartonFreezesOnlyThatCarton() {
        loose(BIN, 5);
        UUID box = carton(BIN, "F", 10, true);
        UUID boxRow = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                box);

        shortPick(CountSlot.carton(new LocationId(BIN), new Sku(sku), lot, new StockPackageId(box)), 10);

        assertThat(frozen()).singleElement().satisfies(f -> {
            assertThat(f.stockItemId()).isEqualTo(boxRow);
            assertThat(f.free()).isEqualTo(10);
        });
        assertThat(atp()).as("the loose units stay sellable").isEqualTo(5);
    }

    // ------------------------------------------------------------------ recount

    @Test
    @DisplayName("a rejected variance is recounted on a new blind MANUAL count; the old line is flagged")
    void recountOpensANewBlindCount() {
        loose(BIN, 10);
        loose(BIN2, 4);
        CycleCountView planned = planShelfA01().count();
        UUID doubted = looseLine(planned, BIN).id();
        cycleCounts.count(planned.id(), doubted, 7, null, counter, false);
        cycleCounts.count(planned.id(), looseLine(planned, BIN2).id(), 4, null, counter, false);
        cycleCounts.submit(planned.id(), counter, false);
        UUID adjustment = (UUID) adjustments().get(0).get("id");

        // While the variance waits for approval, its slot belongs to it: a new plan skips it.
        PlannedCount meanwhile = planShelfA01();
        assertThat(meanwhile.count().lines()).extracting(CycleCountView.Line::locationCode).containsExactly(BIN2);
        assertThat(meanwhile.skipped()).singleElement()
                .satisfies(slot -> assertThat(slot.countNumber()).isEqualTo(planned.number()));
        cycleCounts.cancel(meanwhile.count().id(), "Not needed", "test");
        assertThat(code(catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubted), "Doubtful",
                manager)))).as("not rejected yet").isEqualTo(ErrorCode.CONFLICT);

        operations.reject(adjustment, manager, "Doubtful figure");
        CycleCountView recount = cycleCounts.recount(planned.id(), List.of(doubted), "Check behind the boxes",
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
            assertThat(line.expectedQty()).isEqualTo(10);
        });
        CycleCountView old = cycleCounts.get(planned.id(), true);
        assertThat(looseLine(old, BIN).recountRequired()).isTrue();
        assertThat(looseLine(old, BIN2).recountRequired()).isFalse();
        assertThat(code(catchThrowable(() -> cycleCounts.recount(planned.id(), List.of(doubted), null, manager))))
                .as("the recount holds the slot").isEqualTo(ErrorCode.CYCLE_COUNT_SLOT_BUSY);
    }

    // ------------------------------------------------------------------ who counts

    @Test
    @DisplayName("a count goes only to an active warehouse staff member or manager of its warehouse")
    void assigneeMustBeWarehouseStaff() {
        loose(BIN, 5);
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        UUID inspector = staff("QC_STAFF", WAREHOUSE_HCM);
        UUID unassigned = staff("WAREHOUSE_STAFF");

        assertThat(cycleCounts.assignees(WAREHOUSE_HCM)).extracting(AssignableUser::userId)
                .contains(clerk).doesNotContain(inspector, unassigned);
        assertThat(code(catchThrowable(() -> plan(inspector, List.of(), List.of(), List.of(BIN), List.of(sku)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> plan(unassigned, List.of(), List.of(), List.of(BIN), List.of(sku)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        CycleCountView planned = plan(clerk, List.of(), List.of(), List.of(BIN), List.of(sku)).count();

        assertThat(planned.assignedUserId()).isEqualTo(clerk);
    }

    @Test
    @DisplayName("only the assignee counts an assigned count, unless the caller decides counts")
    void onlyTheAssigneeCounts() {
        loose(BIN, 5);
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        UUID colleague = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView planned = plan(clerk, List.of(), List.of(), List.of(BIN), List.of(sku)).count();
        UUID line = planned.lines().get(0).id();

        assertThat(code(catchThrowable(() -> cycleCounts.count(planned.id(), line, 5, null, colleague, false))))
                .isEqualTo(ErrorCode.CYCLE_COUNT_NOT_ASSIGNEE);
        assertThat(cycleCounts.count(planned.id(), line, 4, null, manager, true).lines().get(0).countedBy())
                .as("who decides counts may count").isEqualTo(manager);
        assertThat(cycleCounts.count(planned.id(), line, 5, null, clerk, false).lines().get(0).countedBy())
                .isEqualTo(clerk);
        assertThat(code(catchThrowable(() -> cycleCounts.assign(planned.id(), colleague))))
                .as("a count being counted keeps its counter").isEqualTo(ErrorCode.CONFLICT);
    }
}
