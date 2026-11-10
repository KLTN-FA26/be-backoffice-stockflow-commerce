package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.inventory.internal.service.StockOperations.PackageAdjustmentRequest;
import com.stockflow.support.CartonRows;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Adjusting a carton by LPN (kltn-docs 20 §4.3, BR-04, BR-05, BR-11; team plan 4C-3) against a real PostgreSQL: a person
 * asks for one whole carton to be written off (DAMAGED, LOST, DATA_CORRECTION, OTHER with a note) or a LOST carton to be
 * restocked (FOUND), never a number of units; nothing changes until another person approves. A held carton, a carton on a
 * count and a carton with an adjustment already waiting are refused; a write-off for LOST leaves the carton LOST, any
 * other leaves it CONSUMED; and a carton with a write-off waiting is out of ATP and out of allocation until it is decided.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CartonAdjustmentIntegrationTest {

    private static final String BIN = "HCM-A01-1-B";
    private static final String BIN2 = "HCM-A01-2-A";
    private static final int CARTON = 20;
    private static final Instant EARLY = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired CycleCounts cycleCounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID clerk;
    private UUID manager;

    @BeforeEach
    void setUp() {
        sku = "CAJ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton adjustment fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton adjustment fixture");
        });
    }

    // ------------------------------------------------------------------ fixtures

    private CartonRows.Carton carton(String location) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, location, 1, CARTON, "L1", EARLY).getFirst());
    }

    private StockAdjustmentSummary ask(CartonRows.Carton carton, AdjustmentDirection direction,
                                       StockAdjustmentReason reason, String note) {
        return operations.requestPackageAdjustment(new PackageAdjustmentRequest(carton.lpn(), direction, reason, note,
                clerk));
    }

    private Throwable askFails(CartonRows.Carton carton, AdjustmentDirection direction, StockAdjustmentReason reason,
                               String note) {
        return catchThrowable(() -> ask(carton, direction, reason, note));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private Map<String, Object> state(CartonRows.Carton carton) {
        return jdbc.queryForMap("""
                select p.status, p.closed_at is not null as closed,
                       (select coalesce(sum(s.on_hand), 0) from inventory.stock_item s where s.stock_package_id = p.id) as held
                  from inventory.stock_package p where p.id = ?""", carton.id());
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    private int adjustmentCount(CartonRows.Carton carton) {
        return jdbc.queryForObject("select count(*) from inventory.stock_adjustment where stock_package_id = ?",
                Integer.class, carton.id());
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991226-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /** One carton reserved and allocated to a customer's order; the row ids the hold sits on. */
    private List<UUID> holdOneCarton() {
        UUID order = confirmedOrder();
        return tx.execute(s -> {
            UUID id = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), CARTON, order))
                    .reservationIds().getFirst();
            inventory.pinReservations(order);
            inventory.allocate(new AllocateStockCommand(order, "HCM"));
            return jdbc.queryForList("select stock_item_id from inventory.stock_reservation where order_id = ?"
                    + " and allocated_from_id = ? and stock_item_id is not null", UUID.class, order, id);
        });
    }

    // ------------------------------------------------------------------ BR-04: the reasons

    @Test
    @DisplayName("DAMAGED: asked, nothing changes; approved by someone else, the carton is CONSUMED whole with a ledger "
            + "line naming it")
    void damagedWritesTheCartonOffAsConsumed() {
        CartonRows.Carton box = carton(BIN);

        StockAdjustmentSummary asked = ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, "Crushed");

        assertThat(asked.status()).isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
        assertThat(asked.stockPackageId()).isEqualTo(box.id());
        assertThat(asked.quantityDelta()).as("the carton's standard count, never typed").isEqualTo(-CARTON);
        assertThat(asked.reason()).isEqualTo(StockAdjustmentReason.DAMAGED);
        assertThat(asked.locationCode()).isEqualTo(BIN);
        assertThat(asked.lotNumber()).isEqualTo("L1");
        assertThat(asked.cycleCountLineId()).isNull();
        assertThat(state(box)).as("nothing changes before approval").containsEntry("status", "IN_STOCK")
                .containsEntry("held", (long) CARTON);
        assertThat(code(catchThrowable(() -> operations.approve(asked.adjustmentId(), clerk))))
                .as("BR-05: four eyes").isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);

        StockAdjustmentSummary posted = operations.approve(asked.adjustmentId(), manager);

        assertThat(posted.status()).isEqualTo(StockAdjustmentStatus.POSTED);
        assertThat(state(box)).containsEntry("status", "CONSUMED").containsEntry("closed", true)
                .containsEntry("held", 0L);
        assertThat(jdbc.queryForList("""
                select quantity, from_stock_package_id, from_location_code from inventory.stock_movement
                 where reference_type = 'STOCK_ADJUSTMENT' and reference_id = ?""", asked.adjustmentId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("quantity")).isEqualTo(CARTON);
                    assertThat(row.get("from_stock_package_id")).isEqualTo(box.id());
                    assertThat(row.get("from_location_code")).isEqualTo(BIN);
                });
    }

    @Test
    @DisplayName("a write-off for LOST leaves the carton LOST so a count or a restock can find it again; "
            + "DATA_CORRECTION and OTHER (with a note) leave it CONSUMED")
    void lostLeavesTheCartonLostAndTheOthersConsumeIt() {
        CartonRows.Carton lost = carton(BIN);
        CartonRows.Carton corrected = carton(BIN);
        CartonRows.Carton other = carton(BIN);

        operations.approve(ask(lost, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST, null).adjustmentId(),
                manager);
        operations.approve(ask(corrected, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DATA_CORRECTION, null)
                .adjustmentId(), manager);
        operations.approve(ask(other, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.OTHER, "Given to the "
                + "supplier as a sample").adjustmentId(), manager);

        assertThat(state(lost)).containsEntry("status", "LOST").containsEntry("held", 0L);
        assertThat(state(corrected)).containsEntry("status", "CONSUMED");
        assertThat(state(other)).containsEntry("status", "CONSUMED");
    }

    @Test
    @DisplayName("OTHER needs a note (blank included); the other reasons do not")
    void otherNeedsANote() {
        CartonRows.Carton box = carton(BIN);

        assertThat(code(askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.OTHER, null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.OTHER, "   ")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(adjustmentCount(box)).as("nothing written by a refusal").isZero();

        assertThat(ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.OTHER, "Water damage").note())
                .isEqualTo("Water damage");
    }

    @Test
    @DisplayName("COUNT_VARIANCE and the reasons of the history (EXPIRED, SCRAP, SAMPLE, SUBCONTRACT_LOSS) are not "
            + "asked of a carton: VALIDATION_FAILED, nothing written")
    void onlyBr04ReasonsAreAskable() {
        CartonRows.Carton box = carton(BIN);

        for (StockAdjustmentReason reason : List.of(StockAdjustmentReason.COUNT_VARIANCE,
                StockAdjustmentReason.EXPIRED, StockAdjustmentReason.SCRAP, StockAdjustmentReason.SAMPLE,
                StockAdjustmentReason.SUBCONTRACT_LOSS)) {
            assertThat(code(askFails(box, AdjustmentDirection.WRITE_OFF, reason, "x"))).as(reason.name())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(adjustmentCount(box)).isZero();
    }

    @Test
    @DisplayName("FOUND restocks a LOST carton whole: IN_STOCK on its own row, same lot, no new layer")
    void foundRestocksALostCarton() {
        CartonRows.Carton box = carton(BIN);
        operations.approve(ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST, null).adjustmentId(),
                manager);
        assertThat(state(box)).containsEntry("status", "LOST");
        assertThat(atp()).isZero();

        StockAdjustmentSummary found = ask(box, AdjustmentDirection.RESTOCK, StockAdjustmentReason.FOUND, null);
        assertThat(found.quantityDelta()).isEqualTo(CARTON);
        operations.approve(found.adjustmentId(), manager);

        assertThat(state(box)).containsEntry("status", "IN_STOCK").containsEntry("closed", false)
                .containsEntry("held", (long) CARTON);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ?", Integer.class, sku))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select lot_number from inventory.stock_item where id = ?", String.class,
                box.stockItemId())).isEqualTo("L1");
        assertThat(atp()).as("found again, it is allocatable again (4C-2 review)").isEqualTo(CARTON);
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a write-off needs a carton in stock; a restock needs a LOST one; both name the carton's state")
    void directionNeedsTheRightState() {
        CartonRows.Carton inStock = carton(BIN);
        CartonRows.Carton consumed = carton(BIN);
        operations.approve(ask(consumed, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null)
                .adjustmentId(), manager);

        assertThat(code(askFails(consumed, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null)))
                .as("already written off").isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(code(askFails(consumed, AdjustmentDirection.RESTOCK, StockAdjustmentReason.FOUND, null)))
                .as("a CONSUMED carton is not found again: only a LOST one").isEqualTo(
                        ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(code(askFails(inStock, AdjustmentDirection.RESTOCK, StockAdjustmentReason.FOUND, null)))
                .as("nothing to restock: it is in stock").isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(adjustmentCount(inStock)).isZero();
    }

    @Test
    @DisplayName("a carton held for a customer's order is not written off: STOCK_PACKAGE_HELD, hold and carton untouched")
    void aHeldCartonIsRefused() {
        CartonRows.Carton box = carton(BIN);
        UUID row = holdOneCarton().getFirst();
        assertThat(row).isEqualTo(box.stockItemId());

        assertThat(code(askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null)))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);

        assertThat(adjustmentCount(box)).isZero();
        assertThat(state(box)).containsEntry("status", "IN_STOCK").containsEntry("held", (long) CARTON);
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where id = ?", Integer.class, row))
                .isEqualTo(CARTON);
    }

    @Test
    @DisplayName("BR-10: a carton on an open count is the count's to correct: STOCK_SLOT_UNDER_COUNT naming the count")
    void aCartonOnACountIsRefused() {
        CartonRows.Carton box = carton(BIN);
        String number = cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, null, true, "Count the bin",
                List.of(), List.of(), List.of(BIN), List.of(sku)), false).count().number();

        Throwable refused = askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        assertThat(code(refused)).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        assertThat(refused).hasMessageContaining(number);
        assertThat(adjustmentCount(box)).isZero();
    }

    @Test
    @DisplayName("one adjustment of a carton waits at a time: STOCK_PACKAGE_ADJUSTMENT_PENDING; decided or withdrawn, "
            + "the carton can be asked about again")
    void onlyOneAdjustmentIsPendingPerCarton() {
        CartonRows.Carton box = carton(BIN);
        StockAdjustmentSummary first = ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        Throwable second = askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST, null);

        assertThat(code(second)).isEqualTo(ErrorCode.STOCK_PACKAGE_ADJUSTMENT_PENDING);
        assertThat(second).hasMessageContaining(first.number());
        assertThat(adjustmentCount(box)).isEqualTo(1);

        operations.reject(first.adjustmentId(), manager, "Not damaged");
        StockAdjustmentSummary again = ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST, null);
        assertThat(code(askFails(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST, null)))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_ADJUSTMENT_PENDING);

        operations.withdraw(again.adjustmentId(), clerk);
        assertThat(ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null).status())
                .isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("an unknown LPN is STOCK_PACKAGE_NOT_FOUND; the LPN is read in any case")
    void lpnLookup() {
        CartonRows.Carton box = carton(BIN);

        assertThat(code(catchThrowable(() -> operations.requestPackageAdjustment(new PackageAdjustmentRequest(
                "LPN-NOPE-0001", AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null, clerk)))))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThat(code(catchThrowable(() -> operations.requestPackageAdjustment(new PackageAdjustmentRequest(null,
                AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null, clerk)))))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThat(operations.requestPackageAdjustment(new PackageAdjustmentRequest(box.lpn().toLowerCase(Locale.ROOT),
                AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null, clerk)).stockPackageId())
                .isEqualTo(box.id());
    }

    // ------------------------------------------------------------------ BR-11: waiting for approval

    @Test
    @DisplayName("BR-11: a pending write-off keeps the carton out of ATP; rejected, it is available again")
    void aPendingWriteOffIsOutOfAtp() {
        CartonRows.Carton a = carton(BIN);
        carton(BIN2);
        assertThat(atp()).isEqualTo(2 * CARTON);

        StockAdjustmentSummary asked = ask(a, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        assertThat(atp()).as("the carton waiting for a decision is not promised").isEqualTo(CARTON);
        operations.reject(asked.adjustmentId(), manager, "It is fine");
        assertThat(atp()).isEqualTo(2 * CARTON);
    }

    @Test
    @DisplayName("BR-11: a pending write-off keeps the carton out of allocation: the order is given the other carton")
    void aPendingWriteOffIsOutOfAllocation() {
        CartonRows.Carton a = carton(BIN);
        CartonRows.Carton b = carton(BIN2);
        ask(a, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        List<UUID> rows = holdOneCarton();

        assertThat(rows).containsExactly(b.stockItemId());
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where id = ?", Integer.class,
                a.stockItemId())).isZero();
    }

    @Test
    @DisplayName("the carton waiting for a decision cannot be reserved beyond what is left: ATP is the other carton only")
    void aPendingWriteOffCannotBeReservedAway() {
        CartonRows.Carton a = carton(BIN);
        carton(BIN2);
        ask(a, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        UUID order = confirmedOrder();
        Throwable tooMuch = catchThrowable(() -> tx.executeWithoutResult(s -> inventory.reserve(
                new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 2 * CARTON, order))));

        assertThat(tooMuch).as("two cartons were asked for, one is promised").isNotNull();
    }

    // ------------------------------------------------------------------ the two paths of a stock adjustment

    @Test
    @DisplayName("a pending carton adjustment is read back with its carton, and listed in the approver's queue")
    void thePendingAdjustmentIsInTheQueue() {
        CartonRows.Carton box = carton(BIN);
        StockAdjustmentSummary asked = ask(box, AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null);

        assertThat(operations.adjustments(StockAdjustmentStatus.PENDING_APPROVAL, sku, null, 0, 20, null).items())
                .extracting(StockAdjustmentSummary::adjustmentId, StockAdjustmentSummary::stockPackageId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(asked.adjustmentId(), box.id()));
    }
}
