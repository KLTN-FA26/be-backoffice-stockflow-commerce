package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-145: four-eyes adjustments, no Spring. The moves of SCRUM-424 this class also covered were arithmetic on a
 * stock row (movable units, merging layers); a carton moves whole and holds no quantity (ADR-0011), so what is left of
 * moving is {@code StockPackageTest}'s.
 */
class StockMoveAndAdjustTest {

    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final UUID CLERK = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    @Nested
    @DisplayName("adjusting stock")
    class Adjusting {

        private StockAdjustment request(AdjustmentDirection direction, AdjustmentReason reason, String note) {
            return StockAdjustment.request("ADJ-20261008-0001", BIN, SKU, "LOT-1", direction, reason, note, CLERK,
                    NOW);
        }

        private StockAdjustment writeOff(AdjustmentReason reason, String note) {
            return request(AdjustmentDirection.WRITE_OFF, reason, note);
        }

        private StockAdjustment restock(AdjustmentReason reason, String note) {
            return request(AdjustmentDirection.RESTOCK, reason, note);
        }

        /**
         * Stock is adjusted by carton only (review C4 P-3), whole: the adjustment says a direction and the service
         * posts the carton's units, under the carton's lock.
         */
        private StockAdjustment cartonWriteOff(AdjustmentReason reason, String note) {
            return writeOff(reason, note).inPackage(new StockPackageId(UUID.randomUUID()));
        }

        @Test
        @DisplayName("an adjustment waits until someone else approves; approving posts it")
        void approvalPosts() {
            StockAdjustment adjustment = cartonWriteOff(AdjustmentReason.DAMAGED, null);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);

            adjustment.approveForCarton(MANAGER, NOW);

            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(adjustment.decidedBy()).isEqualTo(MANAGER);
            assertThat(adjustment.postedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the requester cannot approve or reject their own request")
        void fourEyes() {
            StockAdjustment adjustment = cartonWriteOff(AdjustmentReason.LOST, null);
            assertThatThrownBy(() -> adjustment.approveForCarton(CLERK, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
            assertThatThrownBy(() -> adjustment.reject(CLERK, "no", NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        }

        @Test
        @DisplayName("a decided adjustment cannot be decided again")
        void decidedOnce() {
            StockAdjustment adjustment = cartonWriteOff(AdjustmentReason.LOST, null);
            adjustment.reject(MANAGER, "Counted again, nothing missing", NOW);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.REJECTED);
            assertThatThrownBy(() -> adjustment.approveForCarton(MANAGER, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        }

        @Test
        @DisplayName("OTHER needs a note, an adjustment says which way, rejecting needs a reason")
        void validation() {
            assertThatThrownBy(() -> writeOff(AdjustmentReason.OTHER, "  "))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> request(null, AdjustmentReason.FOUND, null))
                    .isInstanceOf(NullPointerException.class);
            StockAdjustment adjustment = restock(AdjustmentReason.FOUND, null);
            assertThatThrownBy(() -> adjustment.reject(MANAGER, " ", NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("scrap and samples only write stock down, and never a carton (kltn-docs 19, 20 BR-04; review P-2)")
        void scrapAndSampleOnlyWriteDown() {
            for (AdjustmentReason reason : new AdjustmentReason[] {AdjustmentReason.SCRAP, AdjustmentReason.SAMPLE}) {
                assertThatThrownBy(() -> cartonWriteOff(reason, null)).as("%s of a carton", reason)
                        .extracting(e -> ((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.STOCK_ADJUSTMENT_REASON_NOT_FOR_CARTON);
            }
            assertThat(cartonWriteOff(AdjustmentReason.DAMAGED, null).stockPackageId()).isNotNull();

            assertThatThrownBy(() -> restock(AdjustmentReason.SCRAP, null))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> restock(AdjustmentReason.SAMPLE, null))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("the requester withdraws a pending request; stock is untouched and nobody decided it")
        void requesterWithdraws() {
            StockAdjustment adjustment = cartonWriteOff(AdjustmentReason.DAMAGED, null);
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
            StockAdjustment adjustment = writeOff(AdjustmentReason.COUNT_VARIANCE, null)
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
            StockAdjustment adjustment = writeOff(AdjustmentReason.COUNT_VARIANCE, null).forCountLine(line);

            assertThat(adjustment.cycleCountLineId()).isEqualTo(line);
            assertThat(writeOff(AdjustmentReason.DAMAGED, null).cycleCountLineId()).isNull();
        }

        @Test
        @DisplayName("a carton's adjustment keeps its carton and is approved for the carton, not onto one row")
        void cartonAdjustmentKeepsItsCarton() {
            StockPackageId carton = new StockPackageId(UUID.randomUUID());
            StockAdjustment adjustment = writeOff(AdjustmentReason.COUNT_VARIANCE, null).inPackage(carton);

            assertThat(adjustment.stockPackageId()).isEqualTo(carton);
            assertThatThrownBy(() -> adjustment.approveForCarton(CLERK, NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);

            adjustment.approveForCarton(MANAGER, NOW);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(adjustment.postedAt()).isEqualTo(NOW);
            assertThatThrownBy(() -> writeOff(AdjustmentReason.DAMAGED, null).approveForCarton(MANAGER, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("only the requester may withdraw, and only before it is decided")
        void onlyRequesterWithdrawsBeforeDecision() {
            StockAdjustment adjustment = cartonWriteOff(AdjustmentReason.DAMAGED, null);
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
