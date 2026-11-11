package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-424 / SCRUM-145: moving stock between locations and four-eyes adjustments, no Spring. */
class StockMoveAndAdjustTest {

    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final UUID CLERK = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    /** A carton's row: stock is kept in whole cartons (kltn-docs 20 BR-14), so only a carton's row takes units or holds. */
    private static StockItem available(int onHand) {
        StockItem item = new StockItem(StockItemId.newId(), SKU, BIN, "LOT-1", Quantity.of(onHand), StockStatus.AVAILABLE,
                List.of(), 0L, NOW, UUID.randomUUID(), new StockPackageId(UUID.randomUUID()));
        item.pullDomainEvents();
        return item;
    }

    @Nested
    @DisplayName("moving stock")
    class Moving {

        @Test
        @DisplayName("moves only the unreserved units; a hold stays where it was taken")
        void reservedUnitsDoNotMove() {
            StockItem item = available(10);
            item.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);

            assertThat(item.movable()).isEqualTo(Quantity.of(6));
            assertThatThrownBy(() -> item.moveOut(Quantity.of(7)))
                    .isInstanceOf(InsufficientStockException.class);

            item.moveOut(Quantity.of(6));
            assertThat(item.onHand()).isEqualTo(Quantity.of(4));
            assertThat(item.reserved()).isEqualTo(Quantity.of(4));
        }

        @Test
        @DisplayName("a carton row is other goods than a quarantined loose row: nothing merges across (review C4 M-5)")
        void looseArrivalIsRefused() {
            // No loose row is made with units any more (StockItem.arrivedFrom went with review P-3); the loose-row
            // refusals of moveIn, adjust and allocate are StockItemTest's "whole cartons only" cases.
            StockItem source = StockItem.receive(SKU, BIN, "LOT-9", Quantity.of(5));
            assertThat(available(1).canReceiveFrom(source)).as("a carton row is other goods than a quarantined row")
                    .isFalse();
        }

        @Test
        @DisplayName("one lot number with two expiries, or in two lots, is two layers: a put-back never merges them")
        void sameLotNumberOtherExpiryOrLotIsOtherGoods() {
            Instant received = Instant.parse("2026-10-01T02:00:00Z");
            UUID lotA = UUID.randomUUID();
            StockItem early = StockItem.receiveInbound(SKU, BIN, "L01", Quantity.of(4),
                    received, lotA);
            StockItem late = StockItem.receiveInbound(SKU, BIN, "L01", Quantity.of(4),
                    received, UUID.randomUUID());
            StockItem otherLot = StockItem.receiveInbound(SKU, BIN, "L01", Quantity.of(4),
                    received, UUID.randomUUID());
            StockItem untagged = StockItem.receiveInbound(SKU, BIN, "L01", Quantity.of(4),
                    received, null);

            assertThat(late.isSameGoodsAs(early)).as("another expiry").isFalse();
            assertThat(otherLot.isSameGoodsAs(early)).as("another stock_lot").isFalse();
            assertThat(untagged.isSameGoodsAs(early)).as("not tagged by the database yet").isTrue();
        }

        @Test
        @DisplayName("a move needs a quantity")
        void degenerateMovesAreRefused() {
            StockItem item = available(3);
            assertThatThrownBy(() -> item.moveOut(Quantity.ZERO)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("adjusting stock")
    class Adjusting {

        private StockAdjustment writeOff(int delta, AdjustmentReason reason, String note) {
            return StockAdjustment.request("ADJ-20261008-0001", BIN, SKU, "LOT-1", delta, reason, note, CLERK, NOW);
        }

        /** Stock is adjusted by carton only (review C4 P-3): the service posts the units, under the carton's lock. */
        private StockAdjustment cartonWriteOff(int delta, AdjustmentReason reason, String note) {
            return writeOff(delta, reason, note).inPackage(new StockPackageId(UUID.randomUUID()));
        }

        @Test
        @DisplayName("an adjustment waits until someone else approves; approving posts it")
        void approvalPosts() {
            StockAdjustment adjustment = cartonWriteOff(-3, AdjustmentReason.DAMAGED, null);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

            adjustment.approveForCarton(MANAGER, NOW);

            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(adjustment.decidedBy()).isEqualTo(MANAGER);
            assertThat(adjustment.postedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the requester cannot approve or reject their own request")
        void fourEyes() {
            StockAdjustment adjustment = cartonWriteOff(-1, AdjustmentReason.LOST, null);
            assertThatThrownBy(() -> adjustment.approveForCarton(CLERK, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
            assertThatThrownBy(() -> adjustment.reject(CLERK, "no", NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        }

        @Test
        @DisplayName("a decided adjustment cannot be decided again")
        void decidedOnce() {
            StockAdjustment adjustment = cartonWriteOff(-1, AdjustmentReason.LOST, null);
            adjustment.reject(MANAGER, "Counted again, nothing missing", NOW);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.REJECTED);
            assertThatThrownBy(() -> adjustment.approveForCarton(MANAGER, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        }

        @Test
        @DisplayName("OTHER needs a note, zero is not an adjustment, rejecting needs a reason")
        void validation() {
            assertThatThrownBy(() -> writeOff(-1, AdjustmentReason.OTHER, "  "))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> writeOff(0, AdjustmentReason.FOUND, null))
                    .isInstanceOf(IllegalArgumentException.class);
            StockAdjustment adjustment = writeOff(2, AdjustmentReason.FOUND, null);
            assertThatThrownBy(() -> adjustment.reject(MANAGER, " ", NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("scrap and samples only write stock down, and never a carton (kltn-docs 19, 20 BR-04; review P-2)")
        void scrapAndSampleOnlyWriteDown() {
            for (AdjustmentReason reason : new AdjustmentReason[] {AdjustmentReason.SCRAP, AdjustmentReason.SAMPLE}) {
                assertThatThrownBy(() -> cartonWriteOff(-4, reason, null)).as("%s of a carton", reason)
                        .extracting(e -> ((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.STOCK_ADJUSTMENT_REASON_NOT_FOR_CARTON);
            }
            assertThat(cartonWriteOff(-4, AdjustmentReason.DAMAGED, null).stockPackageId()).isNotNull();

            assertThatThrownBy(() -> writeOff(4, AdjustmentReason.SCRAP, null))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> writeOff(1, AdjustmentReason.SAMPLE, null))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("the requester withdraws a pending request; stock is untouched and nobody decided it")
        void requesterWithdraws() {
            StockAdjustment adjustment = cartonWriteOff(-2, AdjustmentReason.DAMAGED, null);
            adjustment.withdraw(CLERK, NOW);

            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.WITHDRAWN);
            assertThat(adjustment.withdrawnAt()).isEqualTo(NOW);
            assertThat(adjustment.decidedBy()).isNull();
            assertThat(adjustment.decidedAt()).isNull();
            assertThatThrownBy(() -> adjustment.approveForCarton(MANAGER, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
            assertThatThrownBy(() -> adjustment.withdraw(CLERK, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        }

        @Test
        @DisplayName("a count variance is approved or rejected, never withdrawn - not even by its requester")
        void countVarianceIsNotWithdrawn() {
            StockAdjustment adjustment = writeOff(-2, AdjustmentReason.COUNT_VARIANCE, null)
                    .forCountLine(UUID.randomUUID());

            assertThatThrownBy(() -> adjustment.withdraw(CLERK, NOW))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.COUNT_VARIANCE_NOT_WITHDRAWABLE);
                        assertThat(e.getMessage()).contains("approved or rejected");
                    });
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            assertThat(adjustment.withdrawnAt()).isNull();
        }

        @Test
        @DisplayName("a count variance names the count line it comes from")
        void countVarianceNamesItsLine() {
            UUID line = UUID.randomUUID();
            StockAdjustment adjustment = writeOff(-2, AdjustmentReason.COUNT_VARIANCE, null).forCountLine(line);

            assertThat(adjustment.cycleCountLineId()).isEqualTo(line);
            assertThat(writeOff(-2, AdjustmentReason.DAMAGED, null).cycleCountLineId()).isNull();
        }

        @Test
        @DisplayName("a carton's adjustment keeps its carton and is approved for the carton, not onto one row")
        void cartonAdjustmentKeepsItsCarton() {
            StockPackageId carton = new StockPackageId(UUID.randomUUID());
            StockAdjustment adjustment = writeOff(-2, AdjustmentReason.COUNT_VARIANCE, null).inPackage(carton);

            assertThat(adjustment.stockPackageId()).isEqualTo(carton);
            assertThatThrownBy(() -> adjustment.approveForCarton(CLERK, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);

            adjustment.approveForCarton(MANAGER, NOW);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(adjustment.postedAt()).isEqualTo(NOW);
            assertThatThrownBy(() -> writeOff(-2, AdjustmentReason.DAMAGED, null).approveForCarton(MANAGER, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("only the requester may withdraw, and only before it is decided")
        void onlyRequesterWithdrawsBeforeDecision() {
            StockAdjustment adjustment = cartonWriteOff(-2, AdjustmentReason.DAMAGED, null);
            assertThatThrownBy(() -> adjustment.withdraw(MANAGER, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_NOT_REQUESTER);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

            adjustment.approveForCarton(MANAGER, NOW);
            assertThatThrownBy(() -> adjustment.withdraw(CLERK, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        }
    }
}
