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

/** The receipt's step order (docs 03 §5.1) and BR-05, without a database. */
class GoodsReceiptTest {

    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private static GoodsReceipt draft() {
        return GoodsReceipt.draft(UUID.randomUUID(), "GR-20261009-0001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), USER, null, null, NOW);
    }

    private static ReceiptLine line(int qty, boolean lotted) {
        return ReceiptLine.counted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty,
                lotted ? "LOT-1" : null, null, null, null);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("confirm goes straight to putaway: there is no quality check at the warehouse (kltn-docs 03 BR-07)")
    void confirmGoesToPutaway() {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(5, false), line(3, true)));
        receipt.confirm(USER, NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.confirmedBy()).isEqualTo(USER);
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
                a.lotNumber(), null, null, null);
        assertThatThrownBy(() -> draft().replaceLines(List.of(a, b))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.DUPLICATE_RECEIPT_LINE);
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
        receipt.replaceLines(List.of(line(2, true)));
        receipt.confirm(USER, NOW);
        assertThatThrownBy(() -> receipt.line(UUID.randomUUID()))
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
    @DisplayName("closeIfPutAway: a receipt not in putaway is left alone")
    void notInPutawayIsLeftAlone() {
        ReceiptLine counted = line(2, true);
        GoodsReceipt draft = draft();
        draft.replaceLines(List.of(counted));
        assertThat(draft.closeIfPutAway(Map.of(counted.id(), 2), NOW)).isFalse();
        assertThat(draft.status()).isEqualTo(GoodsReceiptStatus.DRAFT);

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
    @DisplayName("approve posts the whole count as confirm does - IN_PUTAWAY - in the approver's name")
    void approvePosts() {
        GoodsReceipt plain = waiting(line(5, false));
        plain.approve(MANAGER, "  bonus  ", NOW.plusSeconds(60));
        assertThat(plain.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(plain.confirmedBy()).isEqualTo(MANAGER);
        assertThat(plain.confirmedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(plain.approval()).get().satisfies(a -> {
            assertThat(a.decision()).isEqualTo(ReceiptApproval.Decision.APPROVED);
            assertThat(a.decidedBy()).isEqualTo(MANAGER);
            assertThat(a.reason()).isEqualTo("bonus");
            assertThat(a.requestedBy()).isEqualTo(CLERK);
        });

        GoodsReceipt twoLines = waiting(line(5, false), line(3, true));
        twoLines.approve(MANAGER, "ok", NOW);
        assertThat(twoLines.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(twoLines.lines()).extracting(ReceiptLine::receivedQuantity).containsExactly(5, 3);
    }

    @Test
    @DisplayName("four eyes and a reason: the receiver and whoever asked cannot decide; a blank reason is refused")
    void decisionRules() {
        GoodsReceipt receipt = waiting(line(5, false));
        assertThatThrownBy(() -> receipt.approve(USER, "ok", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.approve(CLERK, "ok", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.rejectExcess(CLERK, "no", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.approve(MANAGER, " ", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
        assertThatThrownBy(() -> receipt.rejectExcess(MANAGER, null, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        assertThat(receipt.approval()).get().extracting(ReceiptApproval::pending).isEqualTo(true);

        GoodsReceipt posted = draft();
        posted.replaceLines(List.of(line(5, false)));
        posted.confirm(USER, NOW);
        assertThatThrownBy(() -> posted.approve(MANAGER, "ok", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("cutBackTo takes the excess off the lots of a PO line from the last one counted; a lot left with nothing goes")
    void cutBack() {
        UUID poLine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReceiptLine a = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 70, "A", null,
                null, null);
        ReceiptLine b = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 30, "B", null,
                null, null);
        ReceiptLine c = new ReceiptLine(UUID.randomUUID(), poLine, UUID.randomUUID(), UUID.randomUUID(), 20, "C", null,
                null, null);
        ReceiptLine unrelated = new ReceiptLine(UUID.randomUUID(), other, UUID.randomUUID(), UUID.randomUUID(), 9, null,
                null, null, null);
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
                null, null, null);
        GoodsReceipt receipt = waiting(a);
        receipt.cutBackTo(Map.of(poLine, 12));
        receipt.rejectExcess(MANAGER, "8 back to the supplier", NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.lines()).extracting(ReceiptLine::receivedQuantity).containsExactly(12);
        assertThat(receipt.approval()).get().extracting(ReceiptApproval::decision)
                .isEqualTo(ReceiptApproval.Decision.EXCESS_REJECTED);

        GoodsReceipt nothing = waiting(a);
        nothing.cutBackTo(Map.of(poLine, 0));
        nothing.rejectExcess(MANAGER, "full", NOW);
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

    // ------------------------------------------------------------------ SCRUM-478: BR-RCP-002

    private static final UUID SUPPLIER = UUID.randomUUID();

    private static GoodsReceipt blindDraft() {
        return GoodsReceipt.blindDraft(UUID.randomUUID(), "GR-20261029-0009", SUPPLIER, UUID.randomUUID(), USER,
                "  gift  ", null, null, NOW);
    }

    private static ReceiptLine itemLine(int qty) {
        return ReceiptLine.counted(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), qty, null, null, null,
                null);
    }

    @Test
    @DisplayName("a receipt without a PO has the supplier and the reason (trimmed), and no PO; one against a PO has neither")
    void blindShape() {
        GoodsReceipt receipt = blindDraft();
        assertThat(receipt.blind()).isTrue();
        assertThat(receipt.purchaseOrderId()).isNull();
        assertThat(receipt.supplierId()).isEqualTo(SUPPLIER);
        assertThat(receipt.blindReason()).isEqualTo("gift");
        assertThat(draft().blind()).isFalse();
        assertThat(draft().supplierId()).isNull();

        for (String reason : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> GoodsReceipt.blindDraft(UUID.randomUUID(), "GR-1", SUPPLIER, UUID.randomUUID(),
                    USER, reason, null, null, NOW)).extracting(GoodsReceiptTest::code)
                    .isEqualTo(ErrorCode.BLIND_RECEIPT_REASON_REQUIRED);
        }
        assertThatThrownBy(() -> GoodsReceipt.blindDraft(UUID.randomUUID(), "GR-1", null, UUID.randomUUID(), USER,
                "gift", null, null, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the lines of a receipt without a PO are told apart by item and lot, not by PO line")
    void blindLinesAreDistinctByItem() {
        GoodsReceipt receipt = blindDraft();
        ReceiptLine a = itemLine(2);
        ReceiptLine same = new ReceiptLine(UUID.randomUUID(), null, a.inventoryItemId(), a.locationId(), 1, null, null,
                null, null);
        assertThatThrownBy(() -> receipt.replaceLines(List.of(a, same))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.DUPLICATE_RECEIPT_LINE);
        receipt.replaceLines(List.of(a, itemLine(3)));
        assertThat(receipt.lines()).hasSize(2);
        assertThat(receipt.quantityOf(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("BR-RCP-002: a receipt without a PO is never confirmed directly; only an approved one is posted")
    void blindNeverConfirmsDirectly() {
        GoodsReceipt receipt = blindDraft();
        receipt.replaceLines(List.of(itemLine(5)));
        assertThatThrownBy(() -> receipt.confirm(USER, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);

        receipt.requestApproval(CLERK, NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        receipt.approve(MANAGER, "checked", NOW.plusSeconds(60));
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.confirmedBy()).isEqualTo(MANAGER);
        assertThat(receipt.approval()).get().extracting(ReceiptApproval::decision)
                .isEqualTo(ReceiptApproval.Decision.APPROVED);
    }

    @Test
    @DisplayName("rejecting cancels the whole receipt with the decision; four eyes and the reason apply")
    void blindReject() {
        GoodsReceipt receipt = blindDraft();
        receipt.replaceLines(List.of(itemLine(5)));
        receipt.requestApproval(CLERK, NOW);
        assertThatThrownBy(() -> receipt.reject(USER, "no", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.reject(CLERK, "no", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
        assertThatThrownBy(() -> receipt.reject(MANAGER, " ", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
        assertThatThrownBy(() -> receipt.rejectExcess(MANAGER, "no", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.cutBackTo(Map.of())).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        receipt.reject(MANAGER, " not ours ", NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(receipt.confirmedAt()).isNull();
        assertThat(receipt.approval()).get().satisfies(a -> {
            assertThat(a.decision()).isEqualTo(ReceiptApproval.Decision.REJECTED);
            assertThat(a.reason()).isEqualTo("not ours");
            assertThat(a.decidedBy()).isEqualTo(MANAGER);
        });

        GoodsReceipt withPo = waiting(line(5, false));
        assertThatThrownBy(() -> withPo.reject(MANAGER, "no", NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    @Test
    @DisplayName("a PO is referenced on a receipt without one - the supplier's, not on a cancelled one, replaceable")
    void referencePurchaseOrder() {
        GoodsReceipt receipt = blindDraft();
        UUID po = UUID.randomUUID();
        receipt.referencePurchaseOrder(po, SUPPLIER);
        assertThat(receipt.referencedPurchaseOrderId()).isEqualTo(po);
        UUID later = UUID.randomUUID();
        receipt.referencePurchaseOrder(later, SUPPLIER);
        assertThat(receipt.referencedPurchaseOrderId()).isEqualTo(later);
        assertThat(receipt.purchaseOrderId()).as("never the receipt's own PO").isNull();

        assertThatThrownBy(() -> receipt.referencePurchaseOrder(po, UUID.randomUUID()))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.RECEIPT_REFERENCE_SUPPLIER_MISMATCH);
        assertThatThrownBy(() -> draft().referencePurchaseOrder(po, SUPPLIER)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        receipt.cancel();
        assertThatThrownBy(() -> receipt.referencePurchaseOrder(po, SUPPLIER)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }
}
