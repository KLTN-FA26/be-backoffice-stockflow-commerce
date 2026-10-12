package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Creation and the replay check of BR-PRD-01, against {@code ck_production_order_source}. */
class ProductionOrderTest {

    private final UUID warehouse = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID line = UUID.randomUUID();
    private final UUID sample = UUID.randomUUID();
    private final ProductionOrder.Work work = new ProductionOrder.Work(warehouse, new Sku("CUP-PP-500"),
            UUID.randomUUID(), "a".repeat(64), 10, LocalDate.of(2026, 11, 1));

    private ProductionOrder forLine(UUID approvedSample, ProductionOrder.Work w) {
        return ProductionOrder.forOrderLine(UUID.randomUUID(), "LSX-20261012-0001", order, line, approvedSample, w);
    }

    @Test
    void bothKindsAreBornPendingPrepressWithTheirOwnSource() {
        ProductionOrder sampleOrder = ProductionOrder.forSample(UUID.randomUUID(), "LSX-1", sample, work);
        ProductionOrder lineOrder = forLine(sample, work);

        assertThat(sampleOrder.status()).isEqualTo(ProductionOrder.Status.PENDING_PREPRESS);
        assertThat(sampleOrder.sourceId()).isEqualTo(sample);
        assertThat(sampleOrder.salesOrderId()).isNull();
        assertThat(lineOrder.status()).isEqualTo(ProductionOrder.Status.PENDING_PREPRESS);
        assertThat(lineOrder.sourceId()).isEqualTo(line);
        assertThat(lineOrder.sampleRequestId()).isNull();
    }

    @Test
    void aRepeatDesignLineMayHaveNoApprovedSampleButASampleOrderNeverHasAnOrder() {
        assertThatCode(() -> forLine(null, work)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductionOrder.restore(UUID.randomUUID(), "LSX-1", ProductionOrder.Type.SAMPLE,
                ProductionOrder.Status.PENDING_PREPRESS, sample, order, null, null, work))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductionOrder.restore(UUID.randomUUID(), "LSX-1", ProductionOrder.Type.ORDER,
                ProductionOrder.Status.PENDING_PREPRESS, null, order, null, null, work))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReplayOfTheSameWorkIsAcceptedWhateverTheStatusReached() {
        ProductionOrder printed = ProductionOrder.restore(UUID.randomUUID(), "LSX-1", ProductionOrder.Type.ORDER,
                ProductionOrder.Status.COMPLETED, null, order, line, sample, work);
        assertThatCode(() -> printed.requireSameWorkAs(forLine(sample, work))).doesNotThrowAnyException();
        assertThat(printed.status()).isEqualTo(ProductionOrder.Status.COMPLETED);
    }

    @Test
    void aReplayThatDisagreesOnAnythingItPrintsIsRefused() {
        ProductionOrder stored = forLine(sample, work);
        List<UnaryOperator<ProductionOrder.Work>> changes = List.of(
                w -> new ProductionOrder.Work(UUID.randomUUID(), w.blankSku(), w.designSnapshotId(), w.designChecksum(),
                        w.plannedQuantity(), w.dueDate()),
                w -> new ProductionOrder.Work(w.warehouseId(), new Sku("BOX-KRAFT-01"), w.designSnapshotId(),
                        w.designChecksum(), w.plannedQuantity(), w.dueDate()),
                w -> new ProductionOrder.Work(w.warehouseId(), w.blankSku(), UUID.randomUUID(), w.designChecksum(),
                        w.plannedQuantity(), w.dueDate()),
                w -> new ProductionOrder.Work(w.warehouseId(), w.blankSku(), w.designSnapshotId(), "b".repeat(64),
                        w.plannedQuantity(), w.dueDate()),
                w -> new ProductionOrder.Work(w.warehouseId(), w.blankSku(), w.designSnapshotId(), w.designChecksum(),
                        11, w.dueDate()),
                w -> new ProductionOrder.Work(w.warehouseId(), w.blankSku(), w.designSnapshotId(), w.designChecksum(),
                        w.plannedQuantity(), null));
        for (UnaryOperator<ProductionOrder.Work> change : changes) {
            assertThatThrownBy(() -> stored.requireSameWorkAs(forLine(sample, change.apply(work))))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.PRODUCTION_SOURCE_CONFLICT);
        }
        assertThatThrownBy(() -> stored.requireSameWorkAs(forLine(UUID.randomUUID(), work)))
                .as("another approved sample").isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> stored.requireSameWorkAs(ProductionOrder.forOrderLine(UUID.randomUUID(), "LSX-2",
                UUID.randomUUID(), line, sample, work))).as("another sales order").isInstanceOf(BusinessException.class);
    }

    @Test
    void theWorkMirrorsTheQuantityAndChecksumChecks() {
        assertThatThrownBy(() -> new ProductionOrder.Work(warehouse, new Sku("CUP-PP-500"), UUID.randomUUID(),
                "a".repeat(64), 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionOrder.Work(warehouse, new Sku("CUP-PP-500"), UUID.randomUUID(),
                " ", 1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prepressPassRecordsWhoCheckedAndMovesToReady() {
        ProductionOrder order = forLine(sample, work);
        UUID staff = UUID.randomUUID();
        java.time.Instant at = java.time.Instant.parse("2026-10-14T03:00:00Z");
        ProductionOrder.Transition t = order.passPrepress(staff, at);
        assertThat(t).isEqualTo(new ProductionOrder.Transition(ProductionOrder.Status.PENDING_PREPRESS,
                ProductionOrder.Status.READY, "Prepress passed"));
        assertThat(order.status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(order.prepressCheckedBy()).isEqualTo(staff);
        assertThatThrownBy(() -> order.passPrepress(staff, at)).as("once")
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    void holdRemembersTheStateAndResumeReturnsToIt() {
        java.time.Instant at = java.time.Instant.parse("2026-10-14T03:00:00Z");
        ProductionOrder order = forLine(sample, work);
        order.passPrepress(UUID.randomUUID(), at);
        order.hold(ProductionOrder.HoldReason.MACHINE_DOWN, " press 2 ", at);
        assertThat(order.status()).isEqualTo(ProductionOrder.Status.ON_HOLD);
        assertThat(order.statusBeforeHold()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(order.holdNote()).isEqualTo("press 2");
        assertThatThrownBy(() -> order.hold(ProductionOrder.HoldReason.MACHINE_DOWN, null, at)).as("already held")
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);

        assertThat(order.resume().to()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(order.statusBeforeHold()).isNull();
        assertThat(order.holdReason()).isNull();
        assertThatThrownBy(order::resume).as("not held")
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> order.hold(ProductionOrder.HoldReason.OTHER, " ", at)).as("OTHER says what")
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(order.status()).isEqualTo(ProductionOrder.Status.READY);
    }

    @Test
    void finishedOrQcOrdersCannotBeHeld() {
        java.time.Instant at = java.time.Instant.parse("2026-10-14T03:00:00Z");
        for (ProductionOrder.Status status : List.of(ProductionOrder.Status.QC, ProductionOrder.Status.COMPLETED,
                ProductionOrder.Status.CANCELLED, ProductionOrder.Status.ON_HOLD)) {
            ProductionOrder stopped = ProductionOrder.restore(UUID.randomUUID(), "LSX-1", ProductionOrder.Type.ORDER,
                    status, null, order, line, sample, work);
            assertThatThrownBy(() -> stopped.hold(ProductionOrder.HoldReason.MACHINE_DOWN, null, at)).as(status.name())
                    .isInstanceOf(BusinessException.class);
        }
    }
}
