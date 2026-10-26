package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.ReceivingPackagePlan.Wanted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reconciling a receipt line's cartons with the operator's list: decides only, mutates nothing. */
class ReceivingPackagePlanTest {

    private static final LocationId RECEIVING = new LocationId("HCM-RCV01");

    private static StockPackage carton(String lpn) {
        return StockPackage.receiving(new StockPackageId(UUID.randomUUID()), lpn, UUID.randomUUID(),
                UUID.randomUUID(), 1000, RECEIVING, UUID.randomUUID());
    }

    private static StockPackage voidCarton(String lpn) {
        StockPackage carton = carton(lpn);
        carton.cancel(Instant.parse("2026-10-26T08:00:00Z"));
        return carton;
    }

    private static void refusedWith(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("entries without an LPN are new cartons, in order")
    void newEntriesAreCounts() {
        ReceivingPackagePlan plan = ReceivingPackagePlan.of(List.of(),
                List.of(new Wanted(null, 1000), new Wanted(null, 980)));

        assertThat(plan.newCounts()).containsExactly(1000, 980);
        assertThat(plan.recounts()).isEmpty();
        assertThat(plan.voided()).isEmpty();
    }

    @Test
    @DisplayName("a known LPN is recounted")
    void knownLpnIsRecounted() {
        StockPackage a = carton("LPN-0001");

        ReceivingPackagePlan plan = ReceivingPackagePlan.of(List.of(a), List.of(new Wanted("LPN-0001", 970)));

        assertThat(plan.recounts()).hasSize(1);
        assertThat(plan.recounts().get(0).carton()).isSameAs(a);
        assertThat(plan.recounts().get(0).quantity()).isEqualTo(970);
        assertThat(plan.voided()).isEmpty();
    }

    @Test
    @DisplayName("a RECEIVING carton left out of the list is voided")
    void missingCartonIsVoided() {
        StockPackage kept = carton("LPN-0001");
        StockPackage dropped = carton("LPN-0002");

        ReceivingPackagePlan plan = ReceivingPackagePlan.of(List.of(kept, dropped),
                List.of(new Wanted("LPN-0001", 1000)));

        assertThat(plan.voided()).containsExactly(dropped);
    }

    @Test
    @DisplayName("a VOID carton left out of the list stays as it is: voiding it again would be refused")
    void voidedCartonIsNotVoidedAgain() {
        StockPackage kept = carton("LPN-0001");
        StockPackage history = voidCarton("LPN-0002");

        ReceivingPackagePlan plan = ReceivingPackagePlan.of(List.of(kept, history),
                List.of(new Wanted("LPN-0001", 1000)));

        assertThat(plan.voided()).isEmpty();
    }

    @Test
    @DisplayName("an LPN of another line is refused")
    void foreignLpnIsRefused() {
        refusedWith(ErrorCode.STOCK_PACKAGE_NOT_FOUND, () -> ReceivingPackagePlan.of(
                List.of(carton("LPN-0001")), List.of(new Wanted("LPN-9999", 10))));
    }

    @Test
    @DisplayName("a VOID carton cannot be recounted")
    void voidCartonCannotBeRecounted() {
        refusedWith(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, () -> ReceivingPackagePlan.of(
                List.of(voidCarton("LPN-0001")), List.of(new Wanted("LPN-0001", 10))));
    }

    @Test
    @DisplayName("one LPN listed twice is refused")
    void duplicateLpnIsRefused() {
        refusedWith(ErrorCode.VALIDATION_FAILED, () -> ReceivingPackagePlan.of(
                List.of(carton("LPN-0001")), List.of(new Wanted("LPN-0001", 10), new Wanted("LPN-0001", 20))));
    }

    @Test
    @DisplayName("a carton of no units is refused")
    void zeroQuantityIsRefused() {
        refusedWith(ErrorCode.VALIDATION_FAILED, () -> ReceivingPackagePlan.of(List.of(), List.of(new Wanted(null, 0))));
    }
}
