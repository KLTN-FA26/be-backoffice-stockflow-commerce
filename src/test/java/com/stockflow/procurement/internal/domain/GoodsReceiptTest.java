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
                qc ? "LOT-1" : null, null, null, qc);
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
                a.lotNumber(), null, null, true, null, null, null, List.of());
        assertThatThrownBy(() -> draft().replaceLines(List.of(a, b))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
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
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

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
}
