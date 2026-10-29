package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The receipt's step order (docs 03 §5.1), BR-05 and BR-08, without a database. */
class GoodsReceiptTest {

    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private static GoodsReceipt draft() {
        return GoodsReceipt.draft(UUID.randomUUID(), "GR-20261009-0001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), USER, null, null, NOW);
    }

    private static ReceiptLine line(int qty, boolean qc) {
        return ReceiptLine.counted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty,
                qc ? "LOT-1" : null, null, null, qc, null);
    }

    private static QcInspection decision(QcOutcome outcome, int qty, String reason) {
        return new QcInspection(UUID.randomUUID(), outcome, qty,
                outcome == QcOutcome.ACCEPTED ? null : UUID.randomUUID(), reason, USER, NOW);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("without a QC line, confirm goes straight to putaway; with one, to QC")
    void confirmRoutes() {
        GoodsReceipt twoStep = draft();
        twoStep.replaceLines(List.of(line(5, false)));
        twoStep.confirm(USER, NOW);
        assertThat(twoStep.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(twoStep.confirmedBy()).isEqualTo(USER);

        GoodsReceipt threeStep = draft();
        threeStep.replaceLines(List.of(line(5, false), line(3, true)));
        threeStep.confirm(USER, NOW);
        assertThat(threeStep.status()).isEqualTo(GoodsReceiptStatus.IN_QC);
    }

    @Test
    @DisplayName("a confirmed receipt is neither recounted nor cancelled (BR-05); an empty one is not confirmed")
    void frozenAfterConfirm() {
        GoodsReceipt empty = draft();
        assertThatThrownBy(() -> empty.confirm(USER, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(5, false)));
        receipt.confirm(USER, NOW);
        assertThatThrownBy(() -> receipt.replaceLines(List.of(line(1, false)))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(receipt::cancel).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("the same PO line and lot twice on one receipt is refused")
    void distinctLines() {
        ReceiptLine a = line(2, true);
        ReceiptLine b = new ReceiptLine(UUID.randomUUID(), a.poLineId(), a.inventoryItemId(), a.locationId(), 1,
                a.lotNumber(), null, null, true, null, null, null, null, List.of());
        assertThatThrownBy(() -> draft().replaceLines(List.of(a, b))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.DUPLICATE_RECEIPT_LINE);
    }

    @Test
    @DisplayName("QC: only after the move to QC, every unit decided once, reasons on non-accepted parts (BR-08)")
    void qcRules() {
        GoodsReceipt receipt = draft();
        ReceiptLine qcLine = line(6, true);
        ReceiptLine plain = line(4, false);
        receipt.replaceLines(List.of(qcLine, plain));
        receipt.confirm(USER, NOW);

        assertThatThrownBy(() -> receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.ACCEPTED, 6, null)), NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.moveToQc(plain.id(), UUID.randomUUID(), USER, NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        receipt.moveToQc(qcLine.id(), UUID.randomUUID(), USER, NOW);
        assertThatThrownBy(() -> receipt.moveToQc(qcLine.id(), UUID.randomUUID(), USER, NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.ACCEPTED, 5, null)), NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.QC_QUANTITY_MISMATCH);
        assertThatThrownBy(() -> receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.ACCEPTED, 5, null),
                decision(QcOutcome.REJECTED, 1, " ")), NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.QC_REASON_REQUIRED);

        receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.ACCEPTED, 5, null),
                decision(QcOutcome.REJECTED, 1, "cracked")), NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.line(qcLine.id()).quantityForPutaway()).isEqualTo(5);
        assertThatThrownBy(() -> receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.ACCEPTED, 6, null)), NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("when QC rejects everything and nothing else waits, the receipt closes")
    void nothingToPutAway() {
        GoodsReceipt receipt = draft();
        ReceiptLine qcLine = line(2, true);
        receipt.replaceLines(List.of(qcLine));
        receipt.confirm(USER, NOW);
        receipt.moveToQc(qcLine.id(), UUID.randomUUID(), USER, NOW);
        receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.REJECTED, 2, "wrong print")), NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(receipt.closedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("PO progress: tolerance rounds down; RECEIVED once nothing is left open")
    void purchaseOrderProgress() {
        var line = new ReceivingPurchaseOrder.Line(UUID.randomUUID(), 1, UUID.randomUUID(), new BigDecimal("10.000"),
                ReceivingPurchaseOrder.LineStatus.OPEN);
        var closed = new ReceivingPurchaseOrder.Line(UUID.randomUUID(), 2, UUID.randomUUID(), BigDecimal.ONE,
                ReceivingPurchaseOrder.LineStatus.CANCELLED);
        assertThat(line.receivableLimit(new BigDecimal("5"))).isEqualTo(10);
        assertThat(line.receivableLimit(new BigDecimal("10"))).isEqualTo(11);
        assertThat(ReceivingPurchaseOrder.lineStatusFor(line, 6)).isEqualTo(ReceivingPurchaseOrder.LineStatus.PARTIALLY_RECEIVED);
        assertThat(ReceivingPurchaseOrder.lineStatusFor(line, 10)).isEqualTo(ReceivingPurchaseOrder.LineStatus.RECEIVED);

        var order = new ReceivingPurchaseOrder(UUID.randomUUID(), "PO-1", ReceivingPurchaseOrder.Status.CONFIRMED,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, false, List.of(line, closed));
        assertThat(order.statusAfter(Map.of(line.id(), ReceivingPurchaseOrder.LineStatus.PARTIALLY_RECEIVED)))
                .isEqualTo(ReceivingPurchaseOrder.Status.PARTIALLY_RECEIVED);
        assertThat(order.statusAfter(Map.of(line.id(), ReceivingPurchaseOrder.LineStatus.RECEIVED)))
                .isEqualTo(ReceivingPurchaseOrder.Status.RECEIVED);
    }

    @Test
    @DisplayName("SCRUM-464: a line the receipt does not have is RECEIPT_LINE_NOT_FOUND, not the receipt itself")
    void unknownLine() {
        GoodsReceipt receipt = draft();
        ReceiptLine qcLine = line(2, true);
        receipt.replaceLines(List.of(qcLine));
        receipt.confirm(USER, NOW);
        assertThatThrownBy(() -> receipt.moveToQc(UUID.randomUUID(), UUID.randomUUID(), USER, NOW))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_FOUND);
    }

    private static GoodsReceipt inPutaway(ReceiptLine... lines) {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(lines));
        receipt.confirm(USER, NOW);
        return receipt;
    }

    @Test
    @DisplayName("closeIfPutAway: closes once every line has had its whole quantity put away")
    void closesWhenEveryLineIsPutAway() {
        ReceiptLine a = line(6, false);
        ReceiptLine b = line(4, false);
        GoodsReceipt receipt = inPutaway(a, b);
        Instant later = NOW.plusSeconds(60);

        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 6, b.id(), 4), later)).isTrue();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(receipt.closedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("closeIfPutAway: stays open while a line is short, or has nothing done at all")
    void staysWhileALineIsShort() {
        ReceiptLine a = line(6, false);
        ReceiptLine b = line(4, false);
        GoodsReceipt receipt = inPutaway(a, b);

        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 6, b.id(), 3), NOW)).isFalse();
        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 6), NOW)).isFalse();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.closedAt()).isNull();
    }

    @Test
    @DisplayName("closeIfPutAway: a QC line with nothing accepted does not hold the receipt open")
    void linesWithNothingToPutAwayDoNotHoldItOpen() {
        ReceiptLine qcLine = line(2, true);
        ReceiptLine plain = line(4, false);
        GoodsReceipt receipt = inPutaway(qcLine, plain);
        receipt.moveToQc(qcLine.id(), UUID.randomUUID(), USER, NOW);
        receipt.inspect(qcLine.id(), List.of(decision(QcOutcome.REJECTED, 2, "wrong print")), NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);

        assertThat(receipt.closeIfPutAway(Map.of(plain.id(), 4), NOW)).isTrue();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
    }

    @Test
    @DisplayName("closeIfPutAway: a receipt not in putaway is left alone")
    void notInPutawayIsLeftAlone() {
        ReceiptLine qcLine = line(2, true);
        GoodsReceipt inQc = inPutaway(qcLine);
        assertThat(inQc.status()).isEqualTo(GoodsReceiptStatus.IN_QC);
        assertThat(inQc.closeIfPutAway(Map.of(), NOW)).isFalse();
        assertThat(inQc.status()).isEqualTo(GoodsReceiptStatus.IN_QC);

        ReceiptLine plain = line(3, false);
        GoodsReceipt closed = inPutaway(plain);
        closed.closeIfPutAway(Map.of(plain.id(), 3), NOW);
        assertThat(closed.closeIfPutAway(Map.of(plain.id(), 3), NOW.plusSeconds(5))).isFalse();
        assertThat(closed.closedAt()).isEqualTo(NOW);
    }

    // ------------------------------------------------------------------ SCRUM-477: BR-RCP-001

    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID CLERK = UUID.randomUUID();

    private static GoodsReceipt waiting(ReceiptLine... lines) {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(lines));
        receipt.requestApproval(CLERK, NOW);
        return receipt;
    }

    @Test
    @DisplayName("asking for approval: a draft with lines waits, counted in nowhere; an empty one or a posted one cannot ask")
    void requestApproval() {
        GoodsReceipt receipt = waiting(line(5, false));
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        assertThat(receipt.confirmedAt()).isNull();
        assertThat(receipt.confirmedBy()).isNull();
        assertThat(receipt.approval()).get().satisfies(a -> {
            assertThat(a.requestedBy()).isEqualTo(CLERK);
            assertThat(a.requestedAt()).isEqualTo(NOW);
            assertThat(a.pending()).isTrue();
        });

        assertThatThrownBy(() -> draft().requestApproval(CLERK, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.requestApproval(CLERK, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("a receipt waiting for approval is neither recounted nor confirmed; it can be cancelled")
    void waitingIsNotEditedOrConfirmed() {
        GoodsReceipt receipt = waiting(line(5, false));
        assertThatThrownBy(() -> receipt.replaceLines(List.of(line(1, false)))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.confirm(CLERK, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        receipt.cancel();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
    }

    @Test
    @DisplayName("approve posts the whole count as confirm does - IN_PUTAWAY, or IN_QC with a QC line - in the approver's name")
    void approvePosts() {
        GoodsReceipt plain = waiting(line(5, false));
        plain.approve(MANAGER, "  bonus  ", NOW.plusSeconds(60), false);
        assertThat(plain.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(plain.confirmedBy()).isEqualTo(MANAGER);
        assertThat(plain.confirmedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(plain.approval()).get().satisfies(a -> {
            assertThat(a.decision()).isEqualTo(ReceiptApproval.Decision.APPROVED);
            assertThat(a.decidedBy()).isEqualTo(MANAGER);
            assertThat(a.reason()).isEqualTo("bonus");
            assertThat(a.requestedBy()).isEqualTo(CLERK);
        });

        GoodsReceipt withQc = waiting(line(5, false), line(3, true));
        withQc.approve(MANAGER, "ok", NOW, false);
        assertThat(withQc.status()).isEqualTo(GoodsReceiptStatus.IN_QC);
        assertThat(withQc.lines()).extracting(ReceiptLine::receivedQuantity).containsExactly(5, 3);
    }

    @Test
    @DisplayName("four eyes and a reason: the receiver and whoever asked cannot decide; a blank reason is refused")
    void decisionRules() {
        GoodsReceipt receipt = waiting(line(5, false));
        assertThatThrownBy(() -> receipt.approve(USER, "ok", NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.approve(CLERK, "ok", NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.rejectExcess(CLERK, "no", NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.approve(MANAGER, " ", NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
        assertThatThrownBy(() -> receipt.rejectExcess(MANAGER, null, NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        assertThat(receipt.approval()).get().extracting(ReceiptApproval::pending).isEqualTo(true);

        GoodsReceipt posted = draft();
        posted.replaceLines(List.of(line(5, false)));
        posted.confirm(USER, NOW);
        assertThatThrownBy(() -> posted.approve(MANAGER, "ok", NOW, false)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("cutBackTo takes the excess off the lots of a PO line from the last one counted; a lot left with nothing goes")
    void cutBack() {
        UUID poLine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReceiptLine a = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 70, "A", null,
                null, false, null, null, null, null, List.of());
        ReceiptLine b = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 30, "B", null,
                null, false, null, null, null, null, List.of());
        ReceiptLine c = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 20, "C", null,
                null, false, null, null, null, null, List.of());
        ReceiptLine unrelated = new ReceiptLine(UUID.randomUUID(), other, UUID.randomUUID(), UUID.randomUUID(), 9, null,
                null, null, false, null, null, null, null, List.of());
        GoodsReceipt receipt = waiting(a, b, c, unrelated);

        receipt.cutBackTo(Map.of(poLine, 85, other, 9));

        assertThat(receipt.lines()).extracting(l -> l.lotNumber() + ":" + l.receivedQuantity())
                .containsExactly("A:70", "B:15", "null:9");
        assertThat(receipt.lines().get(0).id()).as("a cut line keeps its id").isEqualTo(a.id());

        receipt.cutBackTo(Map.of(poLine, 100));
        assertThat(receipt.lines()).as("an allowance above the count cuts nothing").hasSize(3);
    }

    @Test
    @DisplayName("rejecting the excess posts what is left; with nothing left the receipt is cancelled, decision kept")
    void rejectExcess() {
        UUID poLine = UUID.randomUUID();
        ReceiptLine a = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 20, null,
                null, null, false, null, null, null, null, List.of());
        GoodsReceipt receipt = waiting(a);
        receipt.cutBackTo(Map.of(poLine, 12));
        receipt.rejectExcess(MANAGER, "8 back to the supplier", NOW, false);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.lines()).extracting(ReceiptLine::receivedQuantity).containsExactly(12);
        assertThat(receipt.approval()).get().extracting(ReceiptApproval::decision)
                .isEqualTo(ReceiptApproval.Decision.EXCESS_REJECTED);

        GoodsReceipt nothing = waiting(a);
        nothing.cutBackTo(Map.of(poLine, 0));
        nothing.rejectExcess(MANAGER, "full", NOW, false);
        assertThat(nothing.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(nothing.confirmedAt()).isNull();
        assertThat(nothing.approval()).get().extracting(ReceiptApproval::reason).isEqualTo("full");
    }

    @Test
    @DisplayName("only a receipt waiting for approval is cut back")
    void cutBackOnlyWhileWaiting() {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(5, false)));
        assertThatThrownBy(() -> receipt.cutBackTo(Map.of())).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }
}
