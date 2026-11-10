package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.common.domain.Sku;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShippedCartons;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Return receipts against the real database (team plan 4D-2; kltn-docs 03 §4.2 steps 9-12, BR-03, BR-14; 09 BR-06):
 * goods come back through a receipt, scanned by LPN against what their source carried, and into quarantine whole.
 *
 * <p>The cartons and shipments are rows ({@link ShippedCartons}): a shipment that was delivered, one the carrier
 * brought back (RTO) and a second order's, so that "another shipment's carton" is a real carton of another order. Every
 * test reads its own order, so the shared context and database are never counted on to be empty.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ReturnReceiptIntegrationTest {

    private static final String SKU = "SOFA-3S-GREY";
    private static final String QUARANTINE = "HCM-QC01";

    @Autowired ReturnReceiptService receipts;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID staff;
    private UUID customer;

    @BeforeEach
    void setUp() {
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private UUID deliveredOrder() {
        UUID order = tx.execute(s -> ReferenceRows.orderWithLine(entityManager, SKU, "DELIVERED", customer, staff,
                DemoData.WAREHOUSE_HCM));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET warehouse_id = ? WHERE id = ?",
                DemoData.WAREHOUSE_HCM, order));
        return order;
    }

    private ShippedCartons.Shipped shipped(UUID order, int cartons, String lot, String shipmentStatus) {
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, order);
        return ShippedCartons.ship(jdbc, tx, order, line, SKU, cartons, 4, lot, shipmentStatus, staff);
    }

    private <T> T asHcmStaff(Supplier<T> call) {
        CurrentUser clerk = new CurrentUser(staff, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        return WithCurrentUser.call(clerk, DataScope.WAREHOUSE, call);
    }

    private ReturnReceiptView openForShipment(ShippedCartons.Shipped shipped) {
        return asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(shipped.shipmentId(), null, QUARANTINE,
                "back from the carrier"), staff));
    }

    private ReturnReceiptView scan(UUID receipt, String lpn) {
        return asHcmStaff(() -> receipts.scan(receipt, lpn, null, staff));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private Map<String, Object> carton(String lpn) {
        return jdbc.queryForMap("""
                SELECT p.status AS package_status, p.unopened, p.location_code AS package_location, s.status AS row_status,
                       s.location_code, s.lot_number, s.on_hand
                  FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE p.lpn = ?""", lpn);
    }

    private long published(UUID receipt) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE '%ReturnedGoodsReceived' "
                + "AND serialized_event LIKE ?", Long.class, "%" + receipt + "%");
    }

    // ---- scenarios -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an RTO shipment comes back: its cartons scanned by LPN, confirmed - every one is in stock again in "
            + "quarantine, sealed, in its own lot, never available to promise; the receipt is CLOSED and announces "
            + "the goods once, naming no return request")
    void aReturnedShipmentComesBackWhole() {
        UUID order = deliveredOrder();
        ShippedCartons.Shipped shipped = shipped(order, 3, "LOT-RTO-" + order.toString().substring(0, 8), "RETURNED");
        String lot = jdbc.queryForObject("SELECT lot_number FROM inventory.stock_item WHERE stock_package_id = ?",
                String.class, shipped.cartons().getFirst().id());
        int atpBefore = inventory.availableToPromise(new Sku(SKU));
        assertThat(shipped.cartons()).allSatisfy(c -> assertThat(carton(c.lpn()).get("package_status")).isEqualTo("CONSUMED"));

        ReturnReceiptView draft = openForShipment(shipped);
        assertThat(draft.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        assertThat(draft.receiptType()).isEqualTo("RETURN");
        assertThat(draft.orderId()).isEqualTo(order);
        assertThat(draft.shipmentId()).isEqualTo(shipped.shipmentId());
        assertThat(draft.warehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
        assertThat(draft.quarantineLocationCode()).isEqualTo(QUARANTINE);
        assertThat(draft.expectedCartons()).extracting(ReturnReceiptService.ExpectedCarton::lpn)
                .containsExactlyInAnyOrderElementsOf(shipped.lpns());
        assertThat(draft.expectedCartons()).noneMatch(ReturnReceiptService.ExpectedCarton::scanned);

        scan(draft.id(), shipped.lpns().get(0));
        // The same carton scanned again is the same carton.
        ReturnReceiptView scanned = scan(draft.id(), shipped.lpns().get(0).toLowerCase());
        assertThat(scanned.cartons()).hasSize(1);
        scan(draft.id(), shipped.lpns().get(1));
        assertThat(carton(shipped.lpns().get(0)).get("package_status")).as("scanned is not received").isEqualTo("CONSUMED");

        ReturnReceiptView closed = asHcmStaff(() -> receipts.confirm(draft.id(), staff));
        assertThat(closed.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(closed.cartons()).extracting(ReturnReceiptService.CartonView::lpn)
                .containsExactlyInAnyOrder(shipped.lpns().get(0), shipped.lpns().get(1));
        assertThat(closed.confirmedBy()).isEqualTo(staff);
        assertThat(closed.closedAt()).isNotNull();

        for (String lpn : shipped.lpns().subList(0, 2)) {
            Map<String, Object> back = carton(lpn);
            assertThat(back.get("package_status")).isEqualTo("IN_STOCK");
            assertThat(back.get("unopened")).as("sealed: it came back whole").isEqualTo(true);
            assertThat(back.get("package_location")).isEqualTo(QUARANTINE);
            assertThat(back.get("row_status")).as("quarantine, not available").isEqualTo("QUARANTINE");
            assertThat(back.get("location_code")).isEqualTo(QUARANTINE);
            assertThat(back.get("lot_number")).as("the lot it was received in (03 BR-03)").isEqualTo(lot);
            assertThat(((Number) back.get("on_hand")).intValue()).as("the count it left with")
                    .isEqualTo(shipped.cartons().getFirst().quantity());
        }
        assertThat(carton(shipped.lpns().get(2)).get("package_status")).as("not on the receipt: still out")
                .isEqualTo("CONSUMED");
        assertThat(inventory.availableToPromise(new Sku(SKU))).as("returned goods are not sold until released")
                .isEqualTo(atpBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'GOODS_RECEIPT' AND reference_id = ?
                   AND movement_type = 'RECEIPT'""", Long.class, draft.id())).isEqualTo(2);
        assertThat(published(draft.id())).isEqualTo(1);
        // The RTO receipt names no return request: nothing to move on the order side.
        assertThat(jdbc.queryForObject("SELECT return_request_id FROM procurement.goods_receipts WHERE id = ?",
                UUID.class, draft.id())).isNull();

        // Confirmed again: answered as it is, nothing taken twice or announced twice.
        assertThat(asHcmStaff(() -> receipts.confirm(draft.id(), staff)).status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(published(draft.id())).isEqualTo(1);
        // A closed receipt takes no more cartons; the rest of the shipment needs no second receipt to be refused.
        assertThat(code(catchThrowable(() -> scan(draft.id(), shipped.lpns().get(2))))).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThat(code(catchThrowable(() -> openForShipment(shipped)))).as("one receipt per returned shipment")
                .isEqualTo(ErrorCode.RETURN_RECEIPT_ALREADY_OPEN);
    }

    @Test
    @DisplayName("only the source's own cartons are taken: another shipment's carton (even of the same order) and "
            + "another order's carton are refused with RETURN_CARTON_NOT_OF_SOURCE, an unknown LPN too; a carton "
            + "still in the warehouse is refused as in the warehouse")
    void onlyTheSourcesOwnCartons() {
        UUID order = deliveredOrder();
        ShippedCartons.Shipped mine = shipped(order, 2, "LOT-MINE", "RETURNED");
        ShippedCartons.Shipped sameOrderOtherShipment = shipped(order, 1, "LOT-OTHER-S", "DELIVERED");
        ShippedCartons.Shipped otherOrder = shipped(deliveredOrder(), 1, "LOT-OTHER-O", "RETURNED");
        ReturnReceiptView draft = openForShipment(mine);

        for (String lpn : List.of(sameOrderOtherShipment.lpns().getFirst(), otherOrder.lpns().getFirst(), "LPN-NO-SUCH")) {
            Throwable refused = catchThrowable(() -> scan(draft.id(), lpn));
            assertThat(code(refused)).as(lpn).isEqualTo(ErrorCode.RETURN_CARTON_NOT_OF_SOURCE);
        }
        assertThat(code(catchThrowable(() -> scan(draft.id(), " ")))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(asHcmStaff(() -> receipts.get(draft.id())).cartons()).isEmpty();

        // A carton of the shipment that is back in the warehouse already (by hand, outside any receipt).
        String home = mine.lpns().getFirst();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE inventory.stock_package SET status = 'IN_STOCK', unopened = TRUE, closed_at = NULL, "
                    + "location_code = 'HCM-A01-1-A' WHERE lpn = ?", home);
            jdbc.update("UPDATE inventory.stock_item SET on_hand = ? WHERE stock_package_id = (SELECT id FROM "
                    + "inventory.stock_package WHERE lpn = ?)", mine.cartons().getFirst().quantity(), home);
        });
        assertThat(code(catchThrowable(() -> scan(draft.id(), home)))).isEqualTo(ErrorCode.RETURN_CARTON_IN_WAREHOUSE);

        // A carton scanned by mistake comes off the draft; nothing scanned, nothing to confirm.
        scan(draft.id(), mine.lpns().get(1));
        assertThat(asHcmStaff(() -> receipts.removeCarton(draft.id(), mine.lpns().get(1))).cartons()).isEmpty();
        assertThat(code(catchThrowable(() -> asHcmStaff(() -> receipts.confirm(draft.id(), staff)))))
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThat(carton(mine.lpns().get(1)).get("package_status")).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("a shipment the carrier delivered is not received back (SHIPMENT_NOT_RETURNED); a receipt needs a "
            + "QUARANTINE area of the warehouse the goods left from; the clerk of another warehouse is refused")
    void whatItIsOpenedFor() {
        UUID order = deliveredOrder();
        ShippedCartons.Shipped delivered = shipped(order, 1, "LOT-D", "DELIVERED");
        ShippedCartons.Shipped back = shipped(order, 1, "LOT-B", "RETURNED");

        assertThat(code(catchThrowable(() -> openForShipment(delivered)))).isEqualTo(ErrorCode.SHIPMENT_NOT_RETURNED);
        assertThat(code(catchThrowable(() -> asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(
                back.shipmentId(), null, "HCM-A01-1-A", null), staff))))).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        assertThat(code(catchThrowable(() -> asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(
                back.shipmentId(), null, null, null), staff))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(
                null, null, QUARANTINE, null), staff))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(
                UUID.randomUUID(), null, QUARANTINE, null), staff))))).isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);

        CurrentUser elsewhere = new CurrentUser(staff, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("ZZZ"), Set.of(UUID.randomUUID()));
        Throwable outside = catchThrowable(() -> WithCurrentUser.call(elsewhere, DataScope.WAREHOUSE, () -> receipts.create(
                new ReturnReceiptService.Create(back.shipmentId(), null, QUARANTINE, null), staff)));
        assertThat(outside).as("BR-SEC-002: the goods come back to the warehouse they left from").isNotNull();

        // Cancelled, a draft leaves nothing behind and the shipment may be received on a new receipt.
        ReturnReceiptView draft = openForShipment(back);
        scan(draft.id(), back.lpns().getFirst());
        assertThat(asHcmStaff(() -> receipts.cancel(draft.id())).status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(asHcmStaff(() -> receipts.cancel(draft.id())).status()).as("again: as it is").isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(carton(back.lpns().getFirst()).get("package_status")).isEqualTo("CONSUMED");
        assertThat(openForShipment(back).status()).isEqualTo(GoodsReceiptStatus.DRAFT);
    }
}
