package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The life of a carton, as V20261020000100 and V20261111000200 state it a second time (checks and the transition
 * trigger): counted at its standard count only, never opened, and back in stock whole from CONSUMED (team plan 4C-2).
 */
class StockPackageTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final LocationId RECEIVING = new LocationId("HCM-RCV01");

    private static StockPackage labelled() {
        return StockPackage.receiving(new StockPackageId(UUID.randomUUID()), "LPN-20261021-000001", UUID.randomUUID(),
                UUID.randomUUID(), 1000, RECEIVING, UUID.randomUUID());
    }

    private static StockPackage inStock() {
        StockPackage carton = labelled();
        carton.count();
        carton.putInStock();
        return carton;
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION));
    }

    @Test
    @DisplayName("labelled RECEIVING and unopened, nominal = the unit's factor, not yet counted")
    void labelledOnAReceipt() {
        StockPackage carton = labelled();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.RECEIVING);
        assertThat(carton.unopened()).isTrue();
        assertThat(carton.nominalQty()).isEqualTo(1000);
        assertThat(carton.receivedQty()).isNull();
    }

    @Test
    @DisplayName("counted while receiving at the standard count the system assigns (03 BR-13), then fixed in stock")
    void countedThenFixed() {
        StockPackage carton = labelled();
        refused(carton::putInStock);
        carton.count();
        carton.count();
        carton.putInStock();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.receivedQty()).isEqualTo(1000);
        refused(carton::count);
    }

    @Test
    @DisplayName("a draft counted at another figure before 4C-2 does not go into stock as it is: recounted first")
    void aNonStandardDraftIsNotPutInStock() {
        StockPackage old = new StockPackage(new StockPackageId(UUID.randomUUID()), "LPN-20261021-000002",
                UUID.randomUUID(), UUID.randomUUID(), 1000, 980, RECEIVING, StockPackageStatus.RECEIVING, true,
                UUID.randomUUID(), null, 3);
        assertThatThrownBy(old::putInStock).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_PACKAGE_NOT_STANDARD));
        old.count();
        old.putInStock();
        assertThat(old.receivedQty()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a receipt cancelled before posting voids its cartons; nothing else does")
    void voided() {
        StockPackage carton = labelled();
        carton.cancel(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.VOID);
        assertThat(carton.closedAt()).isEqualTo(NOW);
        refused(() -> inStock().cancel(NOW));
    }

    @Test
    @DisplayName("it leaves stock whole: CONSUMED and closed, no longer sealed; a consumed carton is not moved")
    void consumedWhole() {
        StockPackage carton = inStock();
        carton.consume(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
        assertThat(carton.unopened()).isFalse();
        assertThat(carton.closedAt()).isEqualTo(NOW);
        refused(() -> carton.consume(NOW));
        refused(() -> carton.moveTo(new LocationId("HCM-A01-1-A")));
        refused(() -> labelled().consume(NOW));
    }

    @Test
    @DisplayName("CONSUMED -> IN_STOCK: put back or returned whole, sealed again, wherever it is put (20 §4.6 steps 5-6)")
    void restockedWhole() {
        StockPackage carton = inStock();
        carton.consume(NOW);
        LocationId bin = new LocationId("HCM-A02-1-A");

        carton.restock(bin);

        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.unopened()).isTrue();
        assertThat(carton.closedAt()).isNull();
        assertThat(carton.location()).isEqualTo(bin);
        assertThat(carton.receivedQty()).isEqualTo(1000);
        refused(() -> carton.restock(bin));
        refused(() -> labelled().restock(bin));
        StockPackage lost = inStock();
        lost.lose(NOW);
        refused(() -> lost.restock(bin));
    }

    @Test
    @DisplayName("lost is no longer unopened; found again it is back in stock whole, sealed")
    void lostAndFound() {
        StockPackage carton = inStock();
        carton.lose(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
        assertThat(carton.unopened()).isFalse();
        assertThat(carton.closedAt()).isEqualTo(NOW);
        carton.found();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.closedAt()).isNull();
        assertThat(carton.unopened()).as("sealed again: only a sealed carton is allocated").isTrue();
        refused(carton::found);
    }

    @Test
    @DisplayName("moved whole while it is live")
    void moved() {
        StockPackage carton = inStock();
        carton.moveTo(new LocationId("HCM-A01-1-A"));
        assertThat(carton.location()).isEqualTo(new LocationId("HCM-A01-1-A"));
    }

    @Test
    @DisplayName("a row that breaks the schema's checks is never rehydrated")
    void invariants() {
        StockPackageId id = new StockPackageId(UUID.randomUUID());
        UUID item = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        assertThatThrownBy(() -> new StockPackage(id, "bad lpn", item, unit, 5, null, RECEIVING,
                StockPackageStatus.RECEIVING, true, null, null, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StockPackage(id, "LPN-1", item, unit, 0, null, RECEIVING,
                StockPackageStatus.RECEIVING, true, null, null, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StockPackage(id, "LPN-1", item, unit, 5, null, RECEIVING,
                StockPackageStatus.IN_STOCK, true, null, null, 0)).as("in stock, never counted")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StockPackage(id, "LPN-1", item, unit, 5, 5, RECEIVING,
                StockPackageStatus.CONSUMED, false, null, null, 0)).as("closed without closed_at")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StockPackage(id, "LPN-1", item, unit, 5, 5, RECEIVING,
                StockPackageStatus.LOST, true, null, NOW, 0)).as("lost and unopened")
                .isInstanceOf(IllegalStateException.class);
    }
}
