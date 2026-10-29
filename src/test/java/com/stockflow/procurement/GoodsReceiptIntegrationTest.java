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

    private GoodsReceipts.NewLine tableLot(UUID poLine, int qty, String lotNumber, String note) {
        return new GoodsReceipts.NewLine(poLine, qty, lotNumber, LocalDate.now().plusYears(1), "HCM-RCV01", note);
    }

    private List<UUID> lineIds(UUID receiptId, String lotNumber) {
        return jdbc.queryForList("""
                SELECT id FROM procurement.goods_receipt_lines WHERE receipt_id = ? AND lot_number = ?""",
                UUID.class, receiptId, lotNumber);
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

        // SCRUM-464: each missing piece of a part with units is named; same area twice is its own code.
        assertThat(fields(org.assertj.core.api.Assertions.catchThrowable(() -> receipts.inspect(draft.id(), tableLine,
                new GoodsReceipts.Decision(4, new GoodsReceipts.Part(2, "HCM-QC01", " "), null), qc))))
                .containsExactly("quarantined.reason:QC_REASON_REQUIRED");
        assertThat(fields(org.assertj.core.api.Assertions.catchThrowable(() -> receipts.inspect(draft.id(), tableLine,
                new GoodsReceipts.Decision(4, null, new GoodsReceipts.Part(2, null, "cracked")), qc))))
                .containsExactly("rejected.locationCode:QC_TARGET_LOCATION_REQUIRED");
        assertThat(fields(org.assertj.core.api.Assertions.catchThrowable(() -> receipts.inspect(draft.id(), tableLine,
                new GoodsReceipts.Decision(4, new GoodsReceipts.Part(1, "HCM-QC01", "dent"),
                        new GoodsReceipts.Part(1, "HCM-QC01", "crack")), qc))))
                .containsExactly("rejected.locationCode:QC_LOCATIONS_MUST_DIFFER");

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
    @DisplayName("a recounted draft line keeps its id; counts move between lots without passing the tolerance on the way")
    void recountKeepsLineIds() {
        UUID po = confirmedOrder(TABLE, 10);
        UUID line = poLine(po, TABLE);
        String second = lot + "-B";
        String third = lot + "-C";
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        receipts.replaceLines(draft.id(), List.of(tableLot(line, 8, lot, null), tableLot(line, 2, second, null)));
        List<UUID> first = lineIds(draft.id(), lot);
        List<UUID> other = lineIds(draft.id(), second);

        // 8 + 2 to 2 + 8 and back: whichever row is written first, the PO line passes 8 + 8 on the way. The
        // tolerance is checked when the receipt is posted (tg_goods_receipts_tolerance, V20261029002000).
        receipts.replaceLines(draft.id(), List.of(tableLot(line, 2, lot, "short"), tableLot(line, 8, second, null)));
        receipts.replaceLines(draft.id(), List.of(tableLot(line, 8, lot, null), tableLot(line, 2, second, "short")));
        assertThat(lineIds(draft.id(), lot)).as("stock packages point at the line").isEqualTo(first);
        assertThat(lineIds(draft.id(), second)).isEqualTo(other);
        assertThat(jdbc.queryForObject("""
                SELECT received_qty::int || ':' || note FROM procurement.goods_receipt_lines WHERE id = ?""",
                String.class, other.get(0))).isEqualTo("2:short");

        // A lot no longer counted goes, a new one is a new line, inserted after the others went down.
        receipts.replaceLines(draft.id(), List.of(tableLot(line, 1, lot, null), tableLot(line, 9, third, null)));
        assertThat(lineIds(draft.id(), lot)).isEqualTo(first);
        assertThat(lineIds(draft.id(), second)).isEmpty();
        assertThat(lineIds(draft.id(), third)).hasSize(1).doesNotContainAnyElementsOf(other);

        assertThat(receipts.confirm(draft.id(), clerk).status()).isEqualTo(GoodsReceiptStatus.IN_QC);
        assertThat(stock(TABLE, "HCM-RCV01", third)).isEqualTo("INBOUND:9");
    }

    @Test
    @DisplayName("SCRUM-473: a draft line with labelled cartons is not dropped from the count; cancelling voids them")
    void labelledCartons() {
        UUID po = confirmedOrder(TABLE, 10);
        UUID line = poLine(po, TABLE);
        String second = lot + "-B";
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        receipts.replaceLines(draft.id(), List.of(tableLot(line, 5, lot, null), tableLot(line, 5, second, null)));
        UUID labelledLine = lineIds(draft.id(), second).getFirst();
        UUID carton = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID unit = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                    SELECT ?, id, ?, 5 FROM inventory.inventory_items WHERE sku = ?""",
                    unit, "C" + unit.toString().substring(0, 8).toUpperCase(), TABLE);
            jdbc.update("""
                    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                         location_code, goods_receipt_line_id)
                    SELECT ?, ?, id, ?, 5, 'HCM-RCV01', ? FROM inventory.inventory_items WHERE sku = ?""",
                    carton, "IT-" + lot.toUpperCase(), unit, labelledLine, TABLE);
        });

        assertThatThrownBy(() -> receipts.replaceLines(draft.id(), List.of(tableLot(line, 10, lot, null))))
                .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
        assertThat(lineIds(draft.id(), second)).containsExactly(labelledLine);

        receipts.cancel(draft.id());
        assertThat(jdbc.queryForMap("""
                SELECT status, closed_at IS NOT NULL AS closed FROM inventory.stock_package WHERE id = ?""", carton))
                .containsEntry("status", "VOID").containsEntry("closed", true);
    }

    @Test
    @DisplayName("partial receipts move the PO on; a count past the supplier's 5% tolerance is flagged, not refused (BR-02)")
    void partialAndTolerance() {
        UUID po = confirmedOrder(SOFA, 10);
        UUID line = poLine(po, SOFA);

        GoodsReceipts.ReceiptView first = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        receipts.replaceLines(first.id(), List.of(new GoodsReceipts.NewLine(line, 6, null, null, "HCM-RCV01", null)));
        assertThat(receipts.confirm(first.id(), clerk).status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(poStatus(po)).isEqualTo("PARTIALLY_RECEIVED");

        GoodsReceipts.ReceiptView second = receipts.create(new GoodsReceipts.Create(po, null, null), clerk);
        // 6 + 5 = 11 > floor(10 × 1.05) = 10: counted and flagged, not refused (SCRUM-477); confirming it would go to
        // the manager (GoodsReceiptOverToleranceApprovalIntegrationTest).
        GoodsReceipts.ReceiptView over = receipts.replaceLines(second.id(),
                List.of(new GoodsReceipts.NewLine(line, 5, null, null, "HCM-RCV01", null)));
        assertThat(over.lines().get(0).warnings()).containsExactly(GoodsReceipts.OVER_TOLERANCE);
        assertThat(over.warnings()).containsExactly(GoodsReceipts.OVER_TOLERANCE);
        // Recounting a draft's line twice: the row is updated in place, never counted twice by the tolerance.
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

    private static List<String> fields(Throwable thrown) {
        return ((BusinessException) thrown).fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
    }

    @Test
    @DisplayName("SCRUM-464: every line is checked, and each refusal names its field and its own code")
    void refusalsNameTheirFields() {
        UUID po = confirmedOrder(SOFA, 2, TABLE, 2);
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, null, null), clerk).id();
        UUID sofa = poLine(po, SOFA);
        UUID table = poLine(po, TABLE);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> receipts.replaceLines(receiptId, List.of(
                new GoodsReceipts.NewLine(sofa, 1, null, null, "HCM-RCV01", null),
                new GoodsReceipts.NewLine(table, 1, null, null, "HCM-RCV01", null),
                new GoodsReceipts.NewLine(UUID.randomUUID(), 1, null, null, "HCM-RCV01", null),
                new GoodsReceipts.NewLine(sofa, 9, null, null, "HCM-A01-1-A", null))));

        // The sofa going past floor(2 x 1.05) = 2 with line 3 is no refusal (BR-02, SCRUM-477): the manager decides.
        assertThat(code(thrown)).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
        assertThat(fields(thrown)).containsExactly(
                "lines[1].lotNumber:LOT_REQUIRED",
                "lines[1].expiryDate:EXPIRY_REQUIRED",
                "lines[2].purchaseOrderLineId:RECEIPT_LINE_NOT_ON_PO",
                "lines[3].locationCode:LOCATION_AREA_MISMATCH",
                "lines[3].lotNumber:DUPLICATE_RECEIPT_LINE");
        assertThat(receipts.get(receiptId).lines()).as("nothing was saved").isEmpty();

        // The first line at fault decides the top-level code.
        Throwable lotOnly = org.assertj.core.api.Assertions.catchThrowable(() -> receipts.replaceLines(receiptId,
                List.of(new GoodsReceipts.NewLine(sofa, 1, null, null, "HCM-RCV01", null),
                        new GoodsReceipts.NewLine(table, 1, lot, LocalDate.now().minusDays(1), "HCM-RCV01", null))));
        assertThat(code(lotOnly)).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
        assertThat(fields(lotOnly)).containsExactly("lines[1].expiryDate:EXPIRY_NOT_AFTER_RECEIPT");
    }

    private void setMaxShelfLife(Integer days) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE inventory.inventory_items SET max_shelf_life_days = ? WHERE sku = ?", days, TABLE));
    }

    @Test
    @DisplayName("SCRUM-479 / BR-RCP-004: an expiry date beyond the receiving day + the item's maximum shelf life is "
            + "refused on its line; within it, or with no maximum, it is accepted")
    void expiryBeyondMaxShelfLifeIsRefused() {
        UUID po = confirmedOrder(TABLE, 10);
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, null, null), clerk).id();
        UUID line = poLine(po, TABLE);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        setMaxShelfLife(30);
        try {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> receipts.replaceLines(receiptId,
                    List.of(new GoodsReceipts.NewLine(line, 1, lot + "-A", today.plusDays(30), "HCM-RCV01", null),
                            new GoodsReceipts.NewLine(line, 1, lot + "-B", today.plusDays(31), "HCM-RCV01", null))));
            assertThat(code(thrown)).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
            assertThat(fields(thrown)).containsExactly("lines[1].expiryDate:EXPIRY_EXCEEDS_MAX_SHELF_LIFE");
            assertThat(((BusinessException) thrown).fieldErrors().get(0).message())
                    .contains(today.plusDays(30).toString());
            assertThat(receipts.get(receiptId).lines()).as("nothing was saved").isEmpty();

            assertThat(receipts.replaceLines(receiptId, List.of(
                    new GoodsReceipts.NewLine(line, 1, lot + "-A", today.plusDays(30), "HCM-RCV01", null))).lines())
                    .hasSize(1);

            // Earlier than the receiving day: the lower bound is unchanged.
            Throwable early = org.assertj.core.api.Assertions.catchThrowable(() -> receipts.replaceLines(receiptId,
                    List.of(new GoodsReceipts.NewLine(line, 1, lot + "-A", today.minusDays(1), "HCM-RCV01", null))));
            assertThat(fields(early)).containsExactly("lines[0].expiryDate:EXPIRY_NOT_AFTER_RECEIPT");

            // No maximum configured: no upper bound.
            setMaxShelfLife(null);
            assertThat(receipts.replaceLines(receiptId, List.of(
                    new GoodsReceipts.NewLine(line, 1, lot + "-A", today.plusYears(20), "HCM-RCV01", null))).lines())
                    .hasSize(1);
        } finally {
            setMaxShelfLife(null);
        }
    }

    @Test
    @DisplayName("SCRUM-464: a QC line in a warehouse with no QC area is QC_AREA_MISSING; display names and flags")
    void qcAreaMissingAndDisplayFields() {
        UUID po = confirmedOrder(TABLE, 2);
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-464", null), clerk).id();
        GoodsReceipts.ReceiptView counted = receipts.replaceLines(receiptId, List.of(new GoodsReceipts.NewLine(
                poLine(po, TABLE), 2, lot, LocalDate.now().plusYears(1), "HCM-RCV01", null)));
        assertThat(counted.lines()).singleElement().satisfies(l -> {
            assertThat(l.lotTracked()).isTrue();
            assertThat(l.expiryTracked()).isTrue();
            assertThat(l.qcRequired()).isTrue();
        });
        assertThat(counted.warehouseCode()).isEqualTo("HCM");
        assertThat(counted.supplierName()).isNotBlank();
        assertThat(counted.receivedByName()).isNotBlank();

        List<UUID> qcAreas = jdbc.queryForList("""
                SELECT id FROM warehouse.area WHERE warehouse_id = (SELECT warehouse_id FROM procurement.goods_receipts
                 WHERE id = ?) AND type = 'QUALITY_CONTROL' AND status = 'ACTIVE'""", UUID.class, receiptId);
        tx.executeWithoutResult(s -> qcAreas.forEach(id ->
                jdbc.update("UPDATE warehouse.area SET status = 'INACTIVE' WHERE id = ?", id)));
        try {
            assertThatThrownBy(() -> receipts.confirm(receiptId, clerk))
                    .extracting(GoodsReceiptIntegrationTest::code).isEqualTo(ErrorCode.QC_AREA_MISSING);
        } finally {
            tx.executeWithoutResult(s -> qcAreas.forEach(id ->
                    jdbc.update("UPDATE warehouse.area SET status = 'ACTIVE' WHERE id = ?", id)));
        }

        GoodsReceipts.ReceiptView confirmed = receipts.confirm(receiptId, clerk);
        assertThat(confirmed.confirmedByName()).isNotBlank();
        // A part of 0 units goes nowhere: its area is not looked up, even a stale code (SCRUM-464).
        UUID tableLine = confirmed.lines().get(0).id();
        receipts.moveToQc(receiptId, tableLine, "HCM-QCA01", clerk);
        GoodsReceipts.ReceiptView inspected = receipts.inspect(receiptId, tableLine,
                new GoodsReceipts.Decision(2, null, new GoodsReceipts.Part(0, "NO-SUCH-AREA", null)), qc);
        assertThat(inspected.lines().get(0).inspections()).singleElement()
                .satisfies(i -> assertThat(i.inspectedByName()).isNotBlank());
        var row = receipts.list(null, po, null, null, null, null, 0, 20, null).items();
        assertThat(row).singleElement().satisfies(r -> {
            assertThat(r.purchaseOrderNumber()).startsWith("PO-");
            assertThat(r.supplierName()).isEqualTo(confirmed.supplierName());
            assertThat(r.warehouseName()).isNotBlank();
            assertThat(r.receivedByName()).isEqualTo(confirmed.receivedByName());
            assertThat(r.confirmedByName()).isEqualTo(confirmed.confirmedByName());
        });
    }
}
