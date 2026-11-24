package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 Q-18 on the receipt alone (BE-20, BE-43): an opened or empty carton does not enter stock, and a receipt holding
 * one - or loose units - is confirmed only by a warehouse manager who counted nothing on it.
 */
class ReturnReceiptControlsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final UUID CLERK = UUID.randomUUID();
    private static final UUID RECOUNTER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    private static ReturnReceipt draft() {
        return ReturnReceipt.forReturnRequest(UUID.randomUUID(), "GR-RET-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), CLERK, null, NOW);
    }

    private static ReturnReceipt.Carton carton(UUID packageId, int counted, UUID by) {
        return new ReturnReceipt.Carton(UUID.randomUUID(), packageId, "LPN-0001", "CUP-12OZ", "LOT-1", 1000, null, by,
                NOW, counted, by);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("sealed cartons only: anyone confirms, and every carton enters stock")
    void sealedNeedsNoManager() {
        ReturnReceipt receipt = draft();
        receipt.scan(carton(UUID.randomUUID(), 1000, CLERK));
        assertThat(receipt.needsManagerConfirmation()).isFalse();
        assertThat(receipt.stockEntered(receipt.cartons().getFirst())).isTrue();
        receipt.confirm(CLERK, NOW, false);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
    }

    @Test
    @DisplayName("an opened carton (800 of 1 000, or empty): not stock; a manager confirms, never who counted it - "
            + "the scanner or a later recounter")
    void openedNeedsAnotherManager() {
        ReturnReceipt receipt = draft();
        UUID pkg = UUID.randomUUID();
        receipt.scan(carton(pkg, 1000, CLERK));
        receipt.scan(carton(pkg, 800, RECOUNTER), true);
        ReturnReceipt.Carton held = receipt.cartons().getFirst();
        assertThat(held.opened()).isTrue();
        assertThat(held.countedBy()).as("a new count is its recorder's").isEqualTo(RECOUNTER);
        assertThat(held.scannedBy()).isEqualTo(CLERK);
        assertThat(receipt.stockEntered(held)).isFalse();
        assertThat(carton(UUID.randomUUID(), 0, CLERK).stockEntered()).as("empty").isFalse();
        assertThat(receipt.needsManagerConfirmation()).isTrue();

        assertThat(code(catchThrowable(() -> receipt.confirm(MANAGER, NOW, false))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED);
        assertThat(code(catchThrowable(() -> receipt.confirm(CLERK, NOW, true))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION);
        assertThat(code(catchThrowable(() -> receipt.confirm(RECOUNTER, NOW, true))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        receipt.confirm(MANAGER, NOW, true);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
    }

    @Test
    @DisplayName("a condition noted again changes nobody's count")
    void conditionKeepsTheCounter() {
        ReturnReceipt receipt = draft();
        UUID pkg = UUID.randomUUID();
        receipt.scan(carton(pkg, 1000, CLERK));
        receipt.scan(new ReturnReceipt.Carton(UUID.randomUUID(), pkg, "LPN-0001", "CUP-12OZ", "LOT-1", 1000, "wet",
                CLERK, NOW, 1000, RECOUNTER), false);
        assertThat(receipt.cartons().getFirst().condition()).isEqualTo("wet");
        assertThat(receipt.cartons().getFirst().countedBy()).isEqualTo(CLERK);
    }

    @Test
    @DisplayName("loose units: a manager who recorded none of them confirms; the recount is its recorder's")
    void looseNeedsAnotherManager() {
        ReturnReceipt receipt = draft();
        UUID photo = UUID.randomUUID();
        receipt.recordLoose(new ReturnReceipt.LooseLine(UUID.randomUUID(), "CUP-12OZ", null, 50, CLERK, photo));
        receipt.recordLoose(new ReturnReceipt.LooseLine(UUID.randomUUID(), "CUP-12OZ", null, 40, MANAGER, photo));
        assertThat(receipt.looseLines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(40);
            assertThat(l.recordedBy()).isEqualTo(MANAGER);
            assertThat(l.photoId()).isEqualTo(photo);
        });
        assertThat(code(catchThrowable(() -> receipt.confirm(MANAGER, NOW, true))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION);
        receipt.confirm(RECOUNTER, NOW, true);
        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
    }

    @Test
    @DisplayName("a receipt confirmed before Q-18 says what it stored: its opened carton did enter stock then")
    void storedStockEnteredWins() {
        ReturnReceipt receipt = draft();
        receipt.scan(carton(UUID.randomUUID(), 800, CLERK));
        ReturnReceipt.Carton held = receipt.cartons().getFirst();
        receipt.restoreStocked(Map.of(held.id(), true));
        assertThat(receipt.stockEntered(held)).isTrue();
    }
}
