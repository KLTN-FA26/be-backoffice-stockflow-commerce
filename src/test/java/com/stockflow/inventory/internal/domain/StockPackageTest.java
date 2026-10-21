package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The life of a carton, as V20261020000100 states it a second time (checks and the transition trigger). */
class StockPackageTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final LocationId RECEIVING = new LocationId("HCM-RCV01");

    private static StockPackage labelled() {
        return StockPackage.receiving(new StockPackageId(UUID.randomUUID()), "LPN-20261021-000001", UUID.randomUUID(),
                UUID.randomUUID(), 1000, RECEIVING, UUID.randomUUID());
    }

    private static StockPackage inStock() {
        StockPackage carton = labelled();
        carton.count(980);
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
    @DisplayName("counted while receiving, recounted, then fixed once in stock")
    void countedThenFixed() {
        StockPackage carton = labelled();
        refused(carton::putInStock);
        carton.count(990);
        carton.count(980);
        carton.putInStock();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.receivedQty()).isEqualTo(980);
        refused(() -> carton.count(1000));
        assertThatThrownBy(() -> labelled().count(-1)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
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
    @DisplayName("opened stays opened; emptied it is CONSUMED and closed")
    void openedAndEmptied() {
        StockPackage carton = inStock();
        carton.open();
        assertThat(carton.unopened()).isFalse();
        carton.consume(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
        assertThat(carton.closedAt()).isEqualTo(NOW);
        refused(carton::open);
        refused(() -> carton.moveTo(new LocationId("HCM-A01-1-A")));
        refused(() -> labelled().open());
    }

    @Test
    @DisplayName("lost is no longer unopened; found again it is back in stock and open")
    void lostAndFound() {
        StockPackage carton = inStock();
        carton.lose(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
        assertThat(carton.unopened()).isFalse();
        assertThat(carton.closedAt()).isEqualTo(NOW);
        carton.found();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.closedAt()).isNull();
        assertThat(carton.unopened()).isFalse();
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
