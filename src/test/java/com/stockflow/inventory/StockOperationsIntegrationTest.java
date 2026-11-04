package com.stockflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SCRUM-424 / SCRUM-145 against a real database: the stock rows, the ledger line and the adjustment
 * all land, with the table constraints (four eyes, ledger shape, foreign keys) live. Each test
 * works on its own canonical SKU and lot, so it never disturbs the demo stock other tests read.
 */
// DIRECT_DEPENDENCIES: moves ask warehouse :: api whether a location may take stock (issue #67).
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class StockOperationsIntegrationTest {

    private Sku sku;
    private static final String BIN = "HCM-A01-1-B";
    private static final String PACKING = "HCM-PACK01";

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private String lot;
    private UUID clerk;
    private UUID manager;

    @BeforeEach
    void ownLot() {
        lot = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        sku = new Sku("MOVE-" + UUID.randomUUID().toString().substring(0, 8));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        // The demo sofa is explicitly untracked. A test that uses lots needs its own canonical
        // LOT item, rather than violating that live policy or disabling policy enforcement.
        tx.executeWithoutResult(
                s -> {
                    UUID product = Identifiers.newId();
                    jdbc.update(
                            "insert into product.products(id,code,name,slug) values (?,?,?,?)",
                            product,
                            sku.code(),
                            "Move fixture",
                            sku.code().toLowerCase(Locale.ROOT));
                    jdbc.update(
                            """
insert into product.variants(id,product_id,sku,name,attribute_signature)
values (?,?,?,'Move fixture','')
""",
                            Identifiers.newId(),
                            product,
                            sku.code());
                    jdbc.update(
                            """
update inventory.inventory_items set lot_tracked=true,policy_configured=true
where sku=?
""",
                            sku.code());
                });
        insertStock(BIN, 10, "AVAILABLE");
    }

    /**
     * In a transaction: Hikari runs with auto-commit off, so a bare update would be rolled back.
     */
    private void insertStock(String location, int onHand, String status) {
        tx.executeWithoutResult(
                s ->
                        jdbc.update(
                                """
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at)
VALUES (?, ?, ?, ?, ?, 0, ?, 0, NOW())""",
                                Identifiers.newId(),
                                sku.code(),
                                location,
                                lot,
                                onHand,
                                status));
    }

    private Integer onHand(String location) {
        return jdbc.query(
                "SELECT on_hand FROM inventory.stock_item WHERE sku = ? AND location_code = ? AND"
                        + " lot_number = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                sku.code(),
                location,
                lot);
    }

    private MoveStockCommand move(UUID requestId, String from, String to, int quantity) {
        return new MoveStockCommand(
                requestId, sku, lot, from, to, quantity, MoveReference.MOVE_TASK, null, clerk);
    }

    @Test
    @DisplayName(
            "a move takes units from one row, adds them to another, and writes one ledger line")
    void moveWritesStockAndLedger() {
        UUID requestId = UUID.randomUUID();
        StockMove first = inventory.move(move(requestId, BIN, PACKING, 4));

        assertThat(onHand(BIN)).isEqualTo(6);
        assertThat(onHand(PACKING)).isEqualTo(4);
        Map<String, Object> line =
                jdbc.queryForMap(
                        "SELECT * FROM inventory.stock_movement WHERE id = ?", first.movementId());
        assertThat(line)
                .containsEntry("movement_type", "MOVE")
                .containsEntry("from_location_code", BIN)
                .containsEntry("to_location_code", PACKING)
                .containsEntry("quantity", 4)
                .containsEntry("reference_type", "MOVE_TASK")
                .containsEntry("reference_id", requestId)
                .containsEntry("actor_id", clerk);

        // The same request again is the same move, not a second one.
        StockMove replay = inventory.move(move(requestId, BIN, PACKING, 4));
        assertThat(replay.movementId()).isEqualTo(first.movementId());
        assertThat(onHand(BIN)).isEqualTo(6);

        // A second move merges into the row already at the destination.
        inventory.move(move(UUID.randomUUID(), BIN, PACKING, 6));
        assertThat(onHand(BIN)).isZero();
        assertThat(onHand(PACKING)).isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM inventory.stock_item WHERE lot_number = ? AND"
                                        + " location_code = ?",
                                Integer.class,
                                lot,
                                PACKING))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a move refuses more than is unreserved, an unknown location, and stock that is not"
                    + " there")
    void moveRefusals() {
        assertThatThrownBy(() -> inventory.move(move(UUID.randomUUID(), BIN, PACKING, 11)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThatThrownBy(() -> inventory.move(move(UUID.randomUUID(), BIN, "HCM-NOPE99", 1)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        assertThatThrownBy(() -> inventory.move(move(UUID.randomUUID(), PACKING, BIN, 1)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.STOCK_ITEM_NOT_FOUND);
        assertThat(onHand(BIN)).isEqualTo(10);
    }

    @Test
    @DisplayName("a lot held in another status at the destination is not merged into")
    void statusMismatch() {
        insertStock(PACKING, 2, "QUARANTINE");
        assertThatThrownBy(() -> inventory.move(move(UUID.randomUUID(), BIN, PACKING, 1)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
    }

    @Test
    @DisplayName(
            "an adjustment changes nothing until another person approves it, then posts with a"
                    + " ledger line")
    void adjustmentFourEyes() {
        StockAdjustmentSummary requested =
                inventory.requestAdjustment(
                        new RequestAdjustmentCommand(
                                BIN,
                                sku,
                                lot,
                                -3,
                                StockAdjustmentReason.DAMAGED,
                                "Crushed corner",
                                clerk));
        assertThat(requested.status()).isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
        assertThat(requested.number()).matches("ADJ-\\d{8}-\\d{4}");
        assertThat(onHand(BIN)).isEqualTo(10);

        assertThatThrownBy(() -> operations.approve(requested.adjustmentId(), clerk))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);

        StockAdjustmentSummary posted = operations.approve(requested.adjustmentId(), manager);
        assertThat(posted.status()).isEqualTo(StockAdjustmentStatus.POSTED);
        assertThat(onHand(BIN)).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT count(*) FROM inventory.stock_movement
WHERE reference_type = 'STOCK_ADJUSTMENT' AND reference_id = ? AND movement_type = 'ADJUSTMENT'
  AND from_location_code = ? AND to_location_code IS NULL AND quantity = 3""",
                                Integer.class,
                                requested.adjustmentId(),
                                BIN))
                .isEqualTo(1);

        assertThatThrownBy(() -> operations.approve(requested.adjustmentId(), manager))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
    }

    @Test
    @DisplayName(
            "numbers are unique per day, rejection leaves stock alone, and the queue filters by"
                    + " status")
    void numbersRejectionAndQueue() {
        StockAdjustmentSummary a =
                inventory.requestAdjustment(
                        new RequestAdjustmentCommand(
                                BIN, sku, lot, 2, StockAdjustmentReason.FOUND, null, clerk));
        StockAdjustmentSummary b =
                inventory.requestAdjustment(
                        new RequestAdjustmentCommand(
                                BIN, sku, lot, -1, StockAdjustmentReason.LOST, null, clerk));
        assertThat(a.number()).isNotEqualTo(b.number());

        operations.reject(b.adjustmentId(), manager, "Found it on the next shelf");
        assertThat(onHand(BIN)).isEqualTo(10);

        var pending =
                operations.adjustments(
                        StockAdjustmentStatus.PENDING_APPROVAL, sku.code(), BIN, 0, 50, null);
        List<UUID> ids =
                pending.items().stream().map(StockAdjustmentSummary::adjustmentId).toList();
        assertThat(ids).contains(a.adjustmentId()).doesNotContain(b.adjustmentId());

        var history = operations.ledger(sku.code(), BIN, null, 0, 50);
        assertThat(history.items())
                .allSatisfy(
                        line ->
                                assertThat(
                                                BIN.equals(line.fromLocation())
                                                        || BIN.equals(line.toLocation()))
                                        .isTrue());
    }

    @Test
    @DisplayName("SCRUM-459: a SCRAP write-off posts, and a withdrawn request is stored with its time, undecided")
    void scrapAndWithdrawal() {
        StockAdjustmentSummary scrap = inventory.requestAdjustment(new RequestAdjustmentCommand(
                BIN, sku, lot, -2, StockAdjustmentReason.SCRAP, "Misprint at station 1", clerk));
        assertThat(operations.approve(scrap.adjustmentId(), manager).status()).isEqualTo(StockAdjustmentStatus.POSTED);
        assertThat(onHand(BIN)).isEqualTo(8);

        StockAdjustmentSummary sample = inventory.requestAdjustment(new RequestAdjustmentCommand(
                BIN, sku, lot, -1, StockAdjustmentReason.SAMPLE, null, clerk));
        assertThatThrownBy(() -> operations.withdraw(sample.adjustmentId(), manager))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_NOT_REQUESTER);

        StockAdjustmentSummary withdrawn = operations.withdraw(sample.adjustmentId(), clerk);
        assertThat(withdrawn.status()).isEqualTo(StockAdjustmentStatus.WITHDRAWN);
        assertThat(withdrawn.withdrawnAt()).isNotNull();
        assertThat(withdrawn.decidedBy()).isNull();
        assertThat(onHand(BIN)).isEqualTo(8);
        assertThatThrownBy(() -> operations.approve(sample.adjustmentId(), manager))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        assertThat(operations.adjustments(StockAdjustmentStatus.WITHDRAWN, sku.code(), BIN, 0, 50, null).items())
                .extracting(StockAdjustmentSummary::adjustmentId).containsExactly(sample.adjustmentId());
    }

    @Test
    @DisplayName("SCRUM-459: releasing a hold that does not exist is RESERVATION_NOT_FOUND, not a 400")
    void releasingAnUnknownHold() {
        assertThatThrownBy(() -> inventory.release(UUID.randomUUID(), "MANUAL_OVERRIDE"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    // ------------------------------------------------------------------ issue #67

    /** Runs {@code body} with a demo row's status changed, and always puts it back. */
    private void withStatus(String table, String where, Object key, String status, Runnable body) {
        String before = jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + where + " = ?", String.class, key);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE " + table + " SET status = ? WHERE " + where + " = ?", status, key));
        try {
            body.run();
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE " + table + " SET status = ? WHERE " + where + " = ?", before, key));
        }
    }

    private ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        return null;
    }

    @Test
    @DisplayName("issue #67: no move into a BLOCKED bin, a bin of a shelf in MAINTENANCE, or an INACTIVE warehouse")
    void movesOnlyIntoUsableLocations() {
        withStatus("warehouse.storage_location", "location_code", "HCM-B01-2-B", "BLOCKED", () ->
                assertThat(errorOf(() -> inventory.move(move(UUID.randomUUID(), BIN, "HCM-B01-2-B", 1))))
                        .isEqualTo(ErrorCode.LOCATION_NOT_USABLE));

        UUID shelfA02 = jdbc.queryForObject("SELECT id FROM warehouse.shelf WHERE code = 'A02' AND warehouse_id ="
                + " (SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM')", UUID.class);
        withStatus("warehouse.shelf", "id", shelfA02, "MAINTENANCE", () ->
                assertThat(errorOf(() -> inventory.move(move(UUID.randomUUID(), BIN, "HCM-A02-2-B", 1))))
                        .isEqualTo(ErrorCode.LOCATION_NOT_USABLE));

        withStatus("warehouse.warehouse", "prefix", "HCM", "INACTIVE", () ->
                assertThat(errorOf(() -> inventory.move(move(UUID.randomUUID(), BIN, "HCM-B01-1-A", 1))))
                        .isEqualTo(ErrorCode.LOCATION_NOT_USABLE));

        // Nothing moved, nothing written.
        assertThat(onHand(BIN)).isEqualTo(10);
        assertThat(errorOf(() -> inventory.move(move(UUID.randomUUID(), BIN, "HCM-ZZZ-9-Z", 1))))
                .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
    }

    @Test
    @DisplayName("issue #67: a blocked bin can still be emptied, and a lower-case code is found")
    void blockedBinCanBeEmptied() {
        withStatus("warehouse.storage_location", "location_code", BIN, "BLOCKED", () -> {
            inventory.move(move(UUID.randomUUID(), BIN, "hcm-pack01", 3));
            assertThat(onHand(BIN)).isEqualTo(7);
            assertThat(onHand(PACKING)).isEqualTo(3);
        });
    }

    // ---- SCRUM-457 / SCRUM-459 ----------------------------------------------------------------

    private static com.stockflow.common.error.ErrorCode codeOf(Throwable thrown) {
        return ((com.stockflow.common.error.BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("stock changes warehouse only through a transfer order, never by a plain move")
    void aMoveToAnotherWarehouseIsRefused() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> inventory.move(move(UUID.randomUUID(), BIN, "HN-RCV01", 1)));

        assertThat(codeOf(thrown)).isEqualTo(com.stockflow.common.error.ErrorCode.MOVE_ACROSS_WAREHOUSES);
        assertThat(onHand(BIN)).isEqualTo(10);
    }

    @Test
    @DisplayName("a warehouse-bound clerk moves and adjusts only in the warehouses assigned to them")
    void aWarehouseBoundClerkStaysInTheirWarehouses() {
        var elsewhere = com.stockflow.support.TestUsers.warehouseStaff("HN");
        var here = com.stockflow.support.TestUsers.warehouseStaff("HCM");

        Throwable moved = org.assertj.core.api.Assertions.catchThrowable(() ->
                com.stockflow.support.WithCurrentUser.run(elsewhere,
                        () -> inventory.move(move(UUID.randomUUID(), BIN, PACKING, 1))));
        assertThat(codeOf(moved)).isEqualTo(com.stockflow.common.error.ErrorCode.OUT_OF_DATA_SCOPE);
        Throwable adjusted = org.assertj.core.api.Assertions.catchThrowable(() ->
                com.stockflow.support.WithCurrentUser.run(elsewhere, () -> inventory.requestAdjustment(
                        new RequestAdjustmentCommand(BIN, sku, lot, -1, StockAdjustmentReason.DAMAGED, "x", clerk))));
        assertThat(codeOf(adjusted)).isEqualTo(com.stockflow.common.error.ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(onHand(BIN)).isEqualTo(10);

        com.stockflow.support.WithCurrentUser.run(here,
                () -> inventory.move(move(UUID.randomUUID(), BIN, PACKING, 2)));
        assertThat(onHand(PACKING)).isEqualTo(2);

        // Assigned nowhere: nothing in reach, not everything.
        var nowhere = com.stockflow.support.TestUsers.warehouseStaff();
        Throwable none = org.assertj.core.api.Assertions.catchThrowable(() ->
                com.stockflow.support.WithCurrentUser.run(nowhere,
                        () -> inventory.move(move(UUID.randomUUID(), BIN, PACKING, 1))));
        assertThat(codeOf(none)).isEqualTo(com.stockflow.common.error.ErrorCode.OUT_OF_DATA_SCOPE);
    }
}
