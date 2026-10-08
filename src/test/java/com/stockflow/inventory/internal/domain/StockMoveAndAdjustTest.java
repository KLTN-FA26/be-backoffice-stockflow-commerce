package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-424 / SCRUM-145: moving stock between locations and four-eyes adjustments, no Spring. */
class StockMoveAndAdjustTest {

    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final LocationId PRODUCTION = new LocationId("HCM-PRD01");
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final UUID CLERK = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    private static StockItem available(int onHand) {
        StockItem item = new StockItem(StockItemId.newId(), SKU, BIN, "LOT-1", LocalDate.of(2027, 1, 1),
                Quantity.of(onHand), StockStatus.AVAILABLE, List.of(), 0L);
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
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);

            assertThat(item.movable()).isEqualTo(Quantity.of(6));
            assertThatThrownBy(() -> item.moveOut(Quantity.of(7)))
                    .isInstanceOf(InsufficientStockException.class);

            item.moveOut(Quantity.of(6));
            assertThat(item.onHand()).isEqualTo(Quantity.of(4));
            assertThat(item.reserved()).isEqualTo(Quantity.of(4));
        }

        @Test
        @DisplayName("the arriving stock keeps SKU, lot, expiry and status")
        void arrivalCopiesIdentity() {
            StockItem source = StockItem.receive(SKU, BIN, "LOT-9", LocalDate.of(2027, 3, 1), Quantity.of(5));
            StockItem arrived = StockItem.arrivedFrom(source, PRODUCTION, Quantity.of(2));

            assertThat(arrived.location()).isEqualTo(PRODUCTION);
            assertThat(arrived.lotNumber()).isEqualTo("LOT-9");
            assertThat(arrived.expiryDate()).isEqualTo(LocalDate.of(2027, 3, 1));
            // Quarantined goods stay quarantined wherever they go: only QC makes stock sellable.
            assertThat(arrived.status()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(arrived.onHand()).isEqualTo(Quantity.of(2));
            assertThat(arrived.canReceiveFrom(source)).isTrue();
            assertThat(available(1).canReceiveFrom(source)).isFalse();
        }

        @Test
        @DisplayName("a move needs a quantity and two different places")
        void degenerateMovesAreRefused() {
            StockItem item = available(3);
            assertThatThrownBy(() -> item.moveOut(Quantity.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StockItem.arrivedFrom(item, BIN, Quantity.of(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("adjusting stock")
    class Adjusting {

        private StockAdjustment writeOff(int delta, AdjustmentReason reason, String note) {
            return StockAdjustment.request("ADJ-20261008-0001", BIN, SKU, "LOT-1", delta, reason, note, CLERK, NOW);
        }

        @Test
        @DisplayName("nothing changes until someone else approves; approving posts")
        void approvalPosts() {
            StockItem item = available(10);
            StockAdjustment adjustment = writeOff(-3, AdjustmentReason.DAMAGED, null);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));

            adjustment.approveAndPost(MANAGER, item, NOW);

            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(adjustment.decidedBy()).isEqualTo(MANAGER);
            assertThat(adjustment.postedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the requester cannot approve or reject their own request")
        void fourEyes() {
            StockAdjustment adjustment = writeOff(-1, AdjustmentReason.LOST, null);
            assertThatThrownBy(() -> adjustment.approveAndPost(CLERK, available(5), NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
            assertThatThrownBy(() -> adjustment.reject(CLERK, "no", NOW))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        }

        @Test
        @DisplayName("a decided adjustment cannot be decided again")
        void decidedOnce() {
            StockAdjustment adjustment = writeOff(-1, AdjustmentReason.LOST, null);
            adjustment.reject(MANAGER, "Counted again, nothing missing", NOW);
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.REJECTED);
            assertThatThrownBy(() -> adjustment.approveAndPost(MANAGER, available(5), NOW))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        }

        @Test
        @DisplayName("a write-off may not eat into a customer's hold")
        void writeOffBelowReservedIsRefused() {
            StockItem item = available(10);
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(8), NOW);
            StockAdjustment adjustment = writeOff(-3, AdjustmentReason.DAMAGED, null);

            assertThatThrownBy(() -> adjustment.approveAndPost(MANAGER, item, NOW))
                    .isInstanceOf(InsufficientStockException.class);
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(adjustment.status()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
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
    }
}
