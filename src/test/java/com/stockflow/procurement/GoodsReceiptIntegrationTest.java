package com.stockflow.procurement;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.GoodsReceiptConfirmed;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.QcOutcome;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-435 against a real database: the 2-step and 3-step receiving flows (docs 03), the purchase
 * order's progress on the new tables, the INBOUND / QUARANTINE / BLOCKED stock and its ledger, and the
 * rules stated twice (service and trigger). Each test builds its own CONFIRMED order and its own lots,
 * on the demo supplier, items and HCM map (HCM-RCV01 receiving, HCM-QCA01 QC, HCM-QC01 and HCM-RTV01
 * quarantine).
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class GoodsReceiptIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";   // 2 steps: no QC, no lot
    private static final String TABLE = "TABLE-OAK-160"; // 3 steps: QC, lot and expiry

    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;

    private UUID clerk;
    private UUID qc;
    private String lot;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        qc = tx.execute(s -> ReferenceRows.user(entityManager));
        lot = "GR-" + UUID.randomUUID().toString().substring(0, 8);
        recorded.clear();
    }

    /** A CONFIRMED order on the new tables; returns its id. Lines: (sku, ordered). */
    private UUID confirmedOrder(Object... skuAndQty) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-IT-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            for (int i = 0; i < skuAndQty.length; i += 2) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                            ordered_qty, unit_price, version, created_at)
                        SELECT ?, ?, ?, ?, id, ?, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                        Identifiers.newId(), po, revision, i / 2 + 1, skuAndQty[i + 1], skuAndQty[i]);
            }
        });
        return po;
    }

    private UUID poLine(UUID po, String sku) {
        return jdbc.queryForObject("""
                SELECT l.id FROM procurement.purchase_order_lines l JOIN inventory.inventory_items i
                  ON i.id = l.inventory_item_id WHERE l.po_id = ? AND i.sku = ?""", UUID.class, po, sku);
    }

    private String poStatus(UUID po) {
        return jdbc.queryForObject("SELECT status FROM procurement.purchase_orders WHERE id = ?", String.class, po);
    }

    /** Status and units at a location, summed over its stock layers (one layer per receipt). */
    private String stock(String sku, String location, String lotNumber) {
        return jdbc.query("""
                SELECT string_agg(status || ':' || total, ',' ORDER BY status) FROM (
                    SELECT status, SUM(on_hand) AS total FROM inventory.stock_item
                     WHERE sku = ? AND location_code = ? AND COALESCE(lot_number, '') = COALESCE(?, '')
                     GROUP BY status) layers""",
                (rs, n) -> rs.getString(1), sku, location, lotNumber).stream().filter(java.util.Objects::nonNull)
                .findFirst().orElse("none");
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private GoodsReceipts.NewLine table(UUID po, int qty) {
        return new GoodsReceipts.NewLine(poLine(po, TABLE), qty, lot, LocalDate.now().plusYears(1), "HCM-RCV01", null);
    }

    @Test
    @DisplayName("2 + 3 steps: confirm counts INBOUND stock and closes the PO; QC splits the TABLE lot three ways")
    void fullFlow() {
        UUID po = confirmedOrder(SOFA, 4, TABLE, 6);
        int atpBefore = inventory.availableToPromise(new Sku(TABLE));
        String sofaBefore = stock(SOFA, "HCM-RCV01", null);
        int sofaInboundBefore = sofaBefore.equals("none") ? 0 : Integer.parseInt(sofaBefore.split(":")[1]);

        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-001", null), clerk);
        assertThat(draft.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        assertThat(draft.number()).matches("GR-\\d{8}-\\d{4}");

        receipts.replaceLines(draft.id(), List.of(
                new GoodsReceipts.NewLine(poLine(po, SOFA), 4, null, null, "hcm-rcv01", null), table(po, 6)));
        GoodsReceipts.ReceiptView confirmed = receipts.confirm(draft.id(), clerk);

        assertThat(confirmed.status()).isEqualTo(GoodsReceiptStatus.IN_QC);
        assertThat(confirmed.lines()).extracting(GoodsReceipts.LineView::sku, GoodsReceipts.LineView::qcProgress)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(SOFA, GoodsReceipts.QcProgress.NOT_REQUIRED),
                        org.assertj.core.groups.Tuple.tuple(TABLE, GoodsReceipts.QcProgress.AWAITING_MOVE_TO_QC));
        assertThat(poStatus(po)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForList("SELECT status FROM procurement.purchase_order_lines WHERE po_id = ?",
                String.class, po)).containsOnly("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT action || ':' || from_status || '>' || to_status FROM procurement.purchase_order_events
                 WHERE po_id = ? AND payload ->> 'receiptNumber' = ?""", String.class, po, confirmed.number()))
                .isEqualTo("RECEIVED:CONFIRMED>RECEIVED");
        assertThat(stock(SOFA, "HCM-RCV01", null)).isEqualTo("INBOUND:" + (sofaInboundBefore + 4));
        assertThat(stock(TABLE, "HCM-RCV01", lot)).isEqualTo("INBOUND:6");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement m JOIN procurement.goods_receipt_lines l
                  ON l.id = m.reference_id WHERE l.receipt_id = ? AND m.movement_type = 'RECEIPT'
                   AND m.reference_type = 'GOODS_RECEIPT_LINE' AND m.to_status = 'INBOUND'""",
                Integer.class, draft.id())).isEqualTo(2);
        // BR-04: received is not sellable.
        assertThat(inventory.availableToPromise(new Sku(TABLE))).isEqualTo(atpBefore);
        assertThat(recorded.ofType(GoodsReceiptConfirmed.class)).singleElement()
                .satisfies(e -> assertThat(e.lines()).hasSize(2));
        assertThat(recorded.ofType(ReceiptStockReadyForPutaway.class)).singleElement()
                .satisfies(e -> assertThat(e.lines()).singleElement()
                        .satisfies(l -> assertThat(l.sku() + "@" + l.locationCode() + "x" + l.quantity())
                                .isEqualTo(SOFA + "@HCM-RCV01x4")));

        UUID tableLine = confirmed.lines().stream().filter(l -> l.sku().equals(TABLE)).findFirst().orElseThrow().id();
        // BR-08: no QC decision before the goods are in the QC area.
        assertThatThrownBy(() -> receipts.inspect(draft.id(), tableLine, new GoodsReceipts.Decision(6, null, null), qc))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipts.moveToQc(draft.id(), tableLine, "HCM-QC01", clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);

        GoodsReceipts.ReceiptView inQc = receipts.moveToQc(draft.id(), tableLine, "HCM-QCA01", clerk);
        assertThat(inQc.lines()).filteredOn(l -> l.id().equals(tableLine)).singleElement()
                .satisfies(l -> assertThat(l.qcProgress()).isEqualTo(GoodsReceipts.QcProgress.IN_QC_AREA));
        assertThat(stock(TABLE, "HCM-RCV01", lot)).isEqualTo("INBOUND:0");
        assertThat(stock(TABLE, "HCM-QCA01", lot)).isEqualTo("INBOUND:6");

        assertThatThrownBy(() -> receipts.inspect(draft.id(), tableLine, new GoodsReceipts.Decision(3,
                new GoodsReceipts.Part(2, "HCM-QC01", "scratched"), null), qc))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.QC_QUANTITY_MISMATCH);

        GoodsReceipts.ReceiptView decided = receipts.inspect(draft.id(), tableLine, new GoodsReceipts.Decision(3,
                new GoodsReceipts.Part(2, "HCM-QC01", "scratched top"),
                new GoodsReceipts.Part(1, "HCM-RTV01", "cracked leg")), qc);

        assertThat(decided.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(decided.lines()).filteredOn(l -> l.id().equals(tableLine)).singleElement().satisfies(l -> {
            assertThat(l.quantityForPutaway()).isEqualTo(3);
            assertThat(l.inspections()).extracting(GoodsReceipts.InspectionView::outcome)
                    .containsExactlyInAnyOrder(QcOutcome.ACCEPTED, QcOutcome.QUARANTINE, QcOutcome.REJECTED);
        });
        assertThat(stock(TABLE, "HCM-QCA01", lot)).isEqualTo("INBOUND:3");
        assertThat(stock(TABLE, "HCM-QC01", lot)).isEqualTo("QUARANTINE:2");
        assertThat(stock(TABLE, "HCM-RTV01", lot)).isEqualTo("BLOCKED:1");
        assertThat(jdbc.queryForList("""
                SELECT from_status || '>' || to_status FROM inventory.stock_movement
                 WHERE reference_type = 'QC_INSPECTION' AND lot_number = ?""", String.class, lot))
                .containsExactlyInAnyOrder("INBOUND>QUARANTINE", "INBOUND>BLOCKED");
        assertThat(inventory.availableToPromise(new Sku(TABLE))).isEqualTo(atpBefore);
        assertThat(recorded.ofType(ReceiptStockReadyForPutaway.class)).hasSize(2);
    }

    @Test
    @DisplayName("partial receipts move the PO on; the supplier's 5% tolerance caps the total (BR-02)")
    void partialAndTolerance() {
        UUID po = confirmedOrder(SOFA, 10);
        UUID line = poLine(po, SOFA);

        GoodsReceipts.ReceiptView first = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        receipts.replaceLines(first.id(), List.of(new GoodsReceipts.NewLine(line, 6, null, null, "HCM-RCV01", null)));
        assertThat(receipts.confirm(first.id(), clerk).status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(poStatus(po)).isEqualTo("PARTIALLY_RECEIVED");

        GoodsReceipts.ReceiptView second = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        // 6 + 5 = 11 > floor(10 × 1.05) = 10.
        assertThatThrownBy(() -> receipts.replaceLines(second.id(),
                List.of(new GoodsReceipts.NewLine(line, 5, null, null, "HCM-RCV01", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
        // Replacing a draft's lines twice with the same PO line: the old row goes before the new one is inserted.
        receipts.replaceLines(second.id(), List.of(new GoodsReceipts.NewLine(line, 3, null, null, "HCM-RCV01", null)));
        receipts.replaceLines(second.id(), List.of(new GoodsReceipts.NewLine(line, 4, null, null, "HCM-RCV01", "rest")));
        assertThat(receipts.confirm(second.id(), clerk).status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(poStatus(po)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForList("""
                SELECT action FROM procurement.purchase_order_events WHERE po_id = ? ORDER BY created_at""",
                String.class, po)).containsExactly("PARTIALLY_RECEIVED", "RECEIVED");

        // A RECEIVED order takes no more receipts (BR-01).
        assertThatThrownBy(() -> receipts.create(new GoodsReceipts.Create(po, null, null), clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
    }

    @Test
    @DisplayName("refusals: DRAFT PO, wrong area, lot and expiry data, empty confirm, editing a confirmed receipt")
    void refusals() {
        UUID draftPo = jdbc.queryForObject(
                "SELECT id FROM procurement.purchase_orders WHERE po_number = 'PO-HCM-DEMO-0001'", UUID.class);
        assertThatThrownBy(() -> receipts.create(new GoodsReceipts.Create(draftPo, null, null), clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
        assertThatThrownBy(() -> receipts.create(new GoodsReceipts.Create(UUID.randomUUID(), null, null), clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);

        UUID po = confirmedOrder(SOFA, 2, TABLE, 2);
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, null, null), clerk).id();
        UUID sofa = poLine(po, SOFA);
        UUID table = poLine(po, TABLE);
        LocalDate nextYear = LocalDate.now().plusYears(1);

        assertThatThrownBy(() -> receipts.confirm(receiptId, clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(sofa, 1, null, null, "HCM-A01-1-A", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(sofa, 1, null, null, "NO-SUCH-LOC", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(sofa, 1, "L1", null, "HCM-RCV01", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(table, 1, null, nextYear, "HCM-RCV01", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(table, 1, lot, null, "HCM-RCV01", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(table, 1, lot, LocalDate.now().minusDays(1), "HCM-RCV01", null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);

        receipts.replaceLines(receiptId, List.of(new GoodsReceipts.NewLine(sofa, 2, null, null, "HCM-RCV01", null)));
        GoodsReceipts.ReceiptView confirmed = receipts.confirm(receiptId, clerk);
        UUID sofaLine = confirmed.lines().get(0).id();
        assertThatThrownBy(() -> receipts.cancel(receiptId))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipts.replaceLines(receiptId, List.of()))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipts.moveToQc(receiptId, sofaLine, "HCM-QCA01", clerk))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        // BR-05 again, in the database: a confirmed line keeps what was counted.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipt_lines SET received_qty = 1 WHERE id = ?", sofaLine)))
                .hasMessageContaining("BR-05");

        // A draft is cancelled; its order stays receivable.
        UUID other = receipts.create(new GoodsReceipts.Create(po, null, null), clerk).id();
        assertThat(receipts.cancel(other).status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(poStatus(po)).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(receipts.list(List.of(GoodsReceiptStatus.CANCELLED), po, null, null, null, null, 0, 20, null)
                .items()).extracting(GoodsReceipts.Row::id).containsExactly(other);
    }
}
