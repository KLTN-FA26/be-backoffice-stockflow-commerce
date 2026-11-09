package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The receipt's step order (docs 03 §5.1) and BR-05, without a database. Every receipt is against a production order
 * (4A-5); the purchase receipts that were there before are only read, or cancelled while nothing is counted in
 * (4A-8), which the last test pins.
 */
class GoodsReceiptTest {

    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PRODUCTION_ORDER = UUID.randomUUID();

    private static GoodsReceipt draft() {
        return GoodsReceipt.productionDraft(UUID.randomUUID(), "GR-20261109-0001", PRODUCTION_ORDER,
                UUID.randomUUID(), USER, "DN-1", null, NOW);
    }

    /** A line of {@code cartons} cartons of 50, of one production order line, in the production order's lot. */
    private static ReceiptLine line(UUID productionOrderLine, UUID item, int cartons) {
        return ReceiptLine.countedForProductionOrder(UUID.randomUUID(), productionOrderLine, item, UUID.randomUUID(),
                cartons * 50, "PRD-20261109-0001", null, UUID.randomUUID());
    }

    private static ReceiptLine line(int cartons) {
        return line(UUID.randomUUID(), UUID.randomUUID(), cartons);
    }

    /** What a purchase receipt of before 4A-5 looks like when read back: no production order. */
    private static GoodsReceipt legacy(GoodsReceiptStatus status) {
        return new GoodsReceipt(UUID.randomUUID(), "GR-20260901-0001", null, UUID.randomUUID(), NOW, USER, null, null,
                List.of(), status, null, null, null, 0L);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a receipt against a production order names it, and is a DRAFT until it is confirmed")
    void productionShape() {
        GoodsReceipt receipt = draft();
        assertThat(receipt.type()).isEqualTo(GoodsReceiptType.PRODUCTION);
        assertThat(receipt.againstProductionOrder()).isTrue();
        assertThat(receipt.productionOrderId()).isEqualTo(PRODUCTION_ORDER);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        assertThat(legacy(GoodsReceiptStatus.DRAFT).type()).isEqualTo(GoodsReceiptType.PURCHASE);

        assertThatThrownBy(() -> new GoodsReceipt(UUID.randomUUID(), "GR-1", PRODUCTION_ORDER, UUID.randomUUID(), NOW,
                USER, null, null, List.of(), GoodsReceiptStatus.PENDING_APPROVAL, null, null, null, 0L))
                .as("it never waits for approval: nothing over is received").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confirm goes straight to putaway: there is no quality check at the warehouse (kltn-docs 03 BR-07)")
    void confirmGoesToPutaway() {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(2), line(3)));
        receipt.confirm(USER, NOW);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.confirmedBy()).isEqualTo(USER);
        assertThat(receipt.confirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a confirmed receipt is neither recounted nor cancelled (BR-05); an empty one is not confirmed")
    void frozenAfterConfirm() {
        GoodsReceipt empty = draft();
        assertThatThrownBy(() -> empty.confirm(USER, NOW)).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(2)));
        receipt.confirm(USER, NOW);
        assertThatThrownBy(() -> receipt.replaceLines(List.of(line(1)))).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(() -> receipt.requireDraft("change the lines of")).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThatThrownBy(receipt::cancel).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        GoodsReceipt cancelled = draft();
        cancelled.cancel();
        assertThat(cancelled.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
    }

    @Test
    @DisplayName("its lines are production order lines, one each: the same line twice is refused, two of one SKU are not")
    void distinctLines() {
        GoodsReceipt receipt = draft();
        UUID productionLine = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        ReceiptLine first = line(productionLine, item, 3);
        assertThatThrownBy(() -> receipt.replaceLines(List.of(first, line(productionLine, item, 1))))
                .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.DUPLICATE_RECEIPT_LINE);

        receipt.replaceLines(List.of(first, line(UUID.randomUUID(), item, 2)));
        assertThat(receipt.lines()).as("two production order lines of one SKU share its lot").hasSize(2);
        assertThat(first.expiryDate()).as("a production lot has no expiry").isNull();
    }

    @Test
    @DisplayName("SCRUM-464: a line the receipt does not have is RECEIPT_LINE_NOT_FOUND, not the receipt itself")
    void unknownLine() {
        GoodsReceipt receipt = draft();
        receipt.replaceLines(List.of(line(2)));
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
        ReceiptLine a = line(6);
        ReceiptLine b = line(4);
        GoodsReceipt receipt = inPutaway(a, b);
        Instant later = NOW.plusSeconds(60);

        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 300, b.id(), 200), later)).isTrue();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(receipt.closedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("closeIfPutAway: stays open while a line is short, or has nothing done at all")
    void staysWhileALineIsShort() {
        ReceiptLine a = line(6);
        ReceiptLine b = line(4);
        GoodsReceipt receipt = inPutaway(a, b);

        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 300, b.id(), 150), NOW)).isFalse();
        assertThat(receipt.closeIfPutAway(Map.of(a.id(), 300), NOW)).isFalse();
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipt.closedAt()).isNull();
    }

    @Test
    @DisplayName("closeIfPutAway: a receipt not in putaway is left alone")
    void notInPutawayIsLeftAlone() {
        ReceiptLine counted = line(2);
        GoodsReceipt draft = draft();
        draft.replaceLines(List.of(counted));
        assertThat(draft.closeIfPutAway(Map.of(counted.id(), 100), NOW)).isFalse();
        assertThat(draft.status()).isEqualTo(GoodsReceiptStatus.DRAFT);

        ReceiptLine plain = line(3);
        GoodsReceipt closed = inPutaway(plain);
        closed.closeIfPutAway(Map.of(plain.id(), 150), NOW);
        assertThat(closed.closeIfPutAway(Map.of(plain.id(), 150), NOW.plusSeconds(5))).isFalse();
        assertThat(closed.closedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("4A-8: a purchase receipt of before is read and cancelled while it counted nothing in; nothing else moves it")
    void legacyPurchaseReceiptsAreOnlyCancelled() {
        for (GoodsReceiptStatus status : List.of(GoodsReceiptStatus.DRAFT, GoodsReceiptStatus.PENDING_APPROVAL)) {
            GoodsReceipt receipt = legacy(status);
            assertThat(receipt.againstProductionOrder()).isFalse();
            assertThatThrownBy(() -> receipt.replaceLines(List.of(line(1)))).as("recount " + status)
                    .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            assertThatThrownBy(() -> receipt.confirm(USER, NOW)).as("confirm " + status)
                    .extracting(GoodsReceiptTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            receipt.cancel();
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        }
        assertThatThrownBy(() -> legacy(GoodsReceiptStatus.IN_PUTAWAY).cancel()).extracting(GoodsReceiptTest::code)
                .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }
}
