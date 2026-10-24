package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * docs 03 §5 AVAILABLE → DAMAGED on the aggregate: free units leave on a shelf report, an allocation's units leave on a
 * picker's, the damaged row takes them (an impaired allocation with them when nothing replaced them), and BR-STK-007
 * ({@code onHand ≥ reserved}) holds throughout.
 */
class StockItemDamageTest {

    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-10-01T03:00:00Z");
    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final LocationId QUARANTINE = new LocationId("HCM-QC01");

    private static StockItem row(StockStatus status, int onHand, LocalDate expiry) {
        return new StockItem(StockItemId.newId(), SKU, BIN, "L1", expiry, Quantity.of(onHand), status, List.of(), 0L,
                RECEIVED, null);
    }

    private static StockItem available(int onHand) {
        return row(StockStatus.AVAILABLE, onHand, null);
    }

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static Reservation allocation(StockItem item, UUID order, UUID skuReservation, int quantity) {
        return item.allocate(order, skuReservation, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(quantity), NOW);
    }

    @Test
    @DisplayName("a shelf report takes free units only; the holds stay whole")
    void freeUnitsOnly() {
        StockItem item = available(10);
        Reservation held = allocation(item, UUID.randomUUID(), UUID.randomUUID(), 6);

        item.giveUpDamaged(Quantity.of(4), NOW);

        assertThat(item.onHand()).isEqualTo(Quantity.of(6));
        assertThat(item.reserved()).isEqualTo(Quantity.of(6));
        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(refusal(() -> item.giveUpDamaged(Quantity.of(1), NOW)))
                .as("every unit left is held").isEqualTo(ErrorCode.DAMAGE_EXCEEDS_FREE_STOCK);
        assertThat(refusal(() -> available(2).giveUpDamaged(Quantity.of(3), NOW)))
                .as("more than on hand").isEqualTo(ErrorCode.DAMAGE_EXCEEDS_FREE_STOCK);
        assertThat(refusal(() -> available(2).giveUpDamaged(Quantity.ZERO, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("only AVAILABLE, unexpired stock is found damaged; everything else has its own path")
    void onlyAvailableUnexpiredStock() {
        for (StockStatus status : List.of(StockStatus.DAMAGED, StockStatus.EXPIRED, StockStatus.QUARANTINE,
                StockStatus.BLOCKED, StockStatus.INBOUND, StockStatus.IN_PRODUCTION)) {
            assertThat(refusal(() -> row(status, 5, null).giveUpDamaged(Quantity.of(1), NOW))).as(status.name())
                    .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
        }
        StockItem expired = row(StockStatus.AVAILABLE, 5, LocalDate.of(2026, 10, 1));
        assertThat(refusal(() -> expired.giveUpDamaged(Quantity.of(1), NOW))).isEqualTo(ErrorCode.STOCK_EXPIRED);
    }

    @Test
    @DisplayName("a picker's report takes units of the allocation: it keeps the rest, or closes DAMAGED when all")
    void allocationUnits() {
        StockItem item = available(10);
        UUID order = UUID.randomUUID();
        UUID skuReservation = UUID.randomUUID();
        Reservation hold = allocation(item, order, skuReservation, 6);

        StockItem.HeldShare share = item.giveUpDamagedHeld(hold.id(), Quantity.of(2), NOW);

        assertThat(share).isEqualTo(new StockItem.HeldShare(order, skuReservation, Quantity.of(2)));
        assertThat(hold.quantity()).isEqualTo(Quantity.of(4));
        assertThat(hold.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(item.onHand()).isEqualTo(Quantity.of(8));
        assertThat(item.reserved()).isEqualTo(Quantity.of(4));

        item.giveUpDamagedHeld(hold.id(), Quantity.of(4), NOW);
        assertThat(hold.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(hold.releaseReason()).isEqualTo(ReleaseReason.DAMAGED);
        assertThat(item.onHand()).isEqualTo(Quantity.of(4));
        assertThat(item.reserved()).isEqualTo(Quantity.ZERO);
    }

    @Test
    @DisplayName("a picker's report needs a live allocation of an order, and no more units than it keeps")
    void allocationRefusals() {
        StockItem item = available(10);
        Reservation hold = allocation(item, UUID.randomUUID(), UUID.randomUUID(), 3);
        Reservation blanks = item.reserveForProduction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Quantity.of(2), NOW);

        assertThat(refusal(() -> item.giveUpDamagedHeld(hold.id(), Quantity.of(4), NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> item.giveUpDamagedHeld(blanks.id(), Quantity.of(1), NOW)))
                .isEqualTo(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE);
        assertThat(refusal(() -> item.giveUpDamagedHeld(ReservationId.newId(), Quantity.of(1), NOW)))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        item.releaseReservation(hold.id(), ReleaseReason.ORDER_CANCELLED, NOW);
        assertThat(refusal(() -> item.giveUpDamagedHeld(hold.id(), Quantity.of(1), NOW)))
                .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
        assertThat(item.onHand()).as("nothing left on a refusal").isEqualTo(Quantity.of(10));
    }

    @Test
    @DisplayName("the damaged row takes the units, and an impaired allocation that can never be picked")
    void damagedRowTakesThemImpaired() {
        StockItem source = available(5);
        UUID order = UUID.randomUUID();
        Reservation hold = allocation(source, order, UUID.randomUUID(), 5);
        StockItem damaged = StockItem.emptyLayerOf(source, QUARANTINE, StockStatus.DAMAGED);

        StockItem.HeldShare share = source.giveUpDamagedHeld(hold.id(), Quantity.of(2), NOW);
        Reservation impaired = damaged.takeUpDamaged(source, Quantity.of(2), share, UUID.randomUUID(), NOW);

        assertThat(damaged.onHand()).isEqualTo(Quantity.of(2));
        assertThat(damaged.reserved()).isEqualTo(Quantity.of(2));
        assertThat(impaired.orderId()).isEqualTo(order);
        assertThat(impaired.allocatedFrom()).isEqualTo(share.allocatedFrom());
        assertThat(impaired.isPinned()).isTrue();
        assertThat(source.onHand().plus(damaged.onHand())).as("no unit lost").isEqualTo(Quantity.of(5));
        assertThat(refusal(() -> damaged.consumeReservation(impaired.id(), NOW)))
                .as("damaged goods are never picked").isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        // The manager cannot write off or restore units an order still holds.
        assertThat(refusal(() -> damaged.requireUnheldDamaged(Quantity.of(1))))
                .isEqualTo(ErrorCode.DAMAGED_STOCK_HELD);
    }

    @Test
    @DisplayName("restoring takes unheld damaged units that are still there")
    void restoring() {
        StockItem source = available(5);
        StockItem damaged = StockItem.emptyLayerOf(source, QUARANTINE, StockStatus.DAMAGED);
        source.giveUpDamaged(Quantity.of(3), NOW);
        damaged.takeUpDamaged(source, Quantity.of(3), null, null, NOW);

        damaged.giveUpRestored(Quantity.of(2));

        assertThat(damaged.onHand()).isEqualTo(Quantity.of(1));
        assertThat(refusal(() -> damaged.giveUpRestored(Quantity.of(2)))).isEqualTo(ErrorCode.CONFLICT);
        assertThat(refusal(() -> source.requireUnheldDamaged(Quantity.of(1))))
                .as("not a DAMAGED row").isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
    }

    @Test
    @DisplayName("a serial unit is one unit on its damaged row too")
    void serialUnit() {
        StockItem unit = new StockItem(StockItemId.newId(), SKU, BIN, null, null, Quantity.of(1),
                StockStatus.AVAILABLE, List.of(), 0L, RECEIVED, "SN-0001");
        StockItem damaged = StockItem.emptyLayerOf(unit, QUARANTINE, StockStatus.DAMAGED);

        assertThat(refusal(() -> unit.giveUpDamaged(Quantity.of(2), NOW))).isEqualTo(ErrorCode.DAMAGE_EXCEEDS_FREE_STOCK);
        unit.giveUpDamaged(Quantity.of(1), NOW);
        damaged.takeUpDamaged(unit, Quantity.of(1), null, null, NOW);

        assertThat(damaged.serialNumber()).isEqualTo("SN-0001");
        assertThat(unit.onHand()).isEqualTo(Quantity.ZERO);
    }
}
