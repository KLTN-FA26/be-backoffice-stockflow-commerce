package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void finishedOrdersCannotBeHeld() {
        java.time.Instant at = java.time.Instant.parse("2026-10-14T03:00:00Z");
        for (ProductionOrder.Status status : List.of(ProductionOrder.Status.COMPLETED,
                ProductionOrder.Status.CANCELLED, ProductionOrder.Status.ON_HOLD)) {
            ProductionOrder stopped = ProductionOrder.restore(UUID.randomUUID(), "LSX-1", ProductionOrder.Type.ORDER,
                    status, null, order, line, sample, work);
            assertThatThrownBy(() -> stopped.hold(ProductionOrder.HoldReason.MACHINE_DOWN, null, at)).as(status.name())
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void qcCanBeHeldAndResumedAndRecordsNoResultWhileHeld() {
        Instant at = Instant.parse("2026-10-14T03:00:00Z");
        ProductionOrder order = ready();
        order.issueBlanks(10);
        order.startPrinting();
        order.recordOutput(10, 0);
        ProductionOrder.Transition held = order.hold(ProductionOrder.HoldReason.WAITING_CUSTOMER, "colour check", at);
        assertThat(held.from()).isEqualTo(ProductionOrder.Status.QC);
        assertThat(order.statusBeforeHold()).isEqualTo(ProductionOrder.Status.QC);
        assertThatThrownBy(() -> order.recordQc(10, 0, at)).as("no result while held")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> order.cancel("customer gone", at)).as("printed: a stop, not a cancel")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);

        assertThat(order.resume().to()).isEqualTo(ProductionOrder.Status.QC);
        assertThat(order.recordQc(10, 0, at).to()).isEqualTo(ProductionOrder.Status.COMPLETED);
    }

    @Test
    void aHeldQcCanStillBeStoppedAndItsGoodUnitsBecomeScrap() {
        Instant at = Instant.parse("2026-10-14T03:00:00Z");
        ProductionOrder order = ready();
        order.issueBlanks(10);
        order.startPrinting();
        order.recordOutput(8, 1);
        order.hold(ProductionOrder.HoldReason.MACHINE_DOWN, null, at);
        assertThat(order.stop(0, 0, "order cancelled", at)).isEqualTo(7);
        assertThat(order.status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(order.scrap()).isEqualTo(8);
        assertThat(order.statusBeforeHold()).isNull();
    }

    private ProductionOrder ready() {
        ProductionOrder ready = forLine(sample, work);
        ready.passPrepress(UUID.randomUUID(), Instant.parse("2026-10-12T01:00:00Z"));
        return ready;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void blanksAreIssuedUpToWhatIsStillNeededThenTheOrderIsMaterialIssued() {
        ProductionOrder order = ready();
        assertThat(order.issueBlanks(6)).isEmpty();
        assertThat(order.status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(order.outstandingBlanks()).isEqualTo(4);
        assertThatThrownBy(() -> order.issueBlanks(5)).as("BR-PRD-03")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.ISSUE_EXCEEDS_REQUIREMENT);
        assertThat(order.issueBlanks(4)).get().extracting(ProductionOrder.Transition::to)
                .isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        assertThatThrownBy(() -> order.issueBlanks(1)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    void outputKeepsGoodPlusScrapEqualToPrintedAndQcDecidesCompletionOrAReprint() {
        ProductionOrder order = ready();
        order.issueBlanks(10);
        order.startPrinting();
        assertThatThrownBy(() -> order.recordOutput(11, 0)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.recordOutput(3, 4)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(order.recordOutput(9, 1)).extracting(ProductionOrder.Transition::to)
                .containsExactly(ProductionOrder.Status.QC);
        assertThat(order.good() + order.scrap()).isEqualTo(order.printed()).isEqualTo(9);
        assertThat(order.unprintedBlanks()).isEqualTo(1);

        assertThatThrownBy(() -> order.recordQc(5, 2, Instant.now())).as("every waiting unit is inspected")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(order.recordQc(7, 1, Instant.now()).to()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(order.good()).isEqualTo(7);
        assertThat(order.scrap()).isEqualTo(2);
        assertThat(order.outstandingBlanks()).as("10 - 7 good - 1 unprinted").isEqualTo(2);

        order.issueBlanks(2);
        order.startPrinting();
        order.recordOutput(3, 0);
        assertThat(order.recordQc(3, 0, Instant.parse("2026-10-13T00:00:00Z")).to())
                .isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(order.good()).isEqualTo(10);
        assertThat(order.completedAt()).isNotNull();
    }

    @Test
    void aRunWhollySpoiltSkipsQcAndGoesBackToReady() {
        ProductionOrder order = ready();
        order.issueBlanks(10);
        order.startPrinting();
        assertThat(order.recordOutput(2, 2)).extracting(ProductionOrder.Transition::to)
                .containsExactly(ProductionOrder.Status.QC, ProductionOrder.Status.READY);
        assertThat(order.outstandingBlanks()).as("10 good needed, 8 unprinted blanks left: 2 more").isEqualTo(2);
    }

    @Test
    void cancelOnlyBeforePrintingAndStopKeepsWhatWasPrinted() {
        ProductionOrder held = ready();
        held.hold(ProductionOrder.HoldReason.WAITING_CUSTOMER, null, Instant.now());
        assertThat(held.cancel("Customer withdrew", Instant.now()).to()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(held.holdReason()).isNull();

        ProductionOrder printing = ready();
        printing.issueBlanks(10);
        printing.startPrinting();
        assertThatThrownBy(() -> printing.cancel("late", Instant.now())).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> printing.stop(0, 0, " ", Instant.now())).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(printing.stop(4, 1, "Customer cancelled", Instant.now())).as("good units sold to nobody")
                .isEqualTo(3);
        assertThat(printing.status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(printing.printed()).isEqualTo(4);
        assertThat(printing.scrap()).isEqualTo(4);
        assertThat(printing.good()).isZero();
        assertThat(printing.unprintedBlanks()).isEqualTo(6);

        ProductionOrder fresh = ready();
        assertThatThrownBy(() -> fresh.stop(0, 0, "nothing printed", Instant.now())).as("cancel, not stop")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    void aSplitTakesUnissuedUnitsFromAnInHouseOrderAndTheChildInheritsItsPrepress() {
        ProductionOrder parent = ready();
        ProductionOrder sampleOrder = ProductionOrder.forSample(UUID.randomUUID(), "LSX-S", sample, work);
        assertThatThrownBy(() -> sampleOrder.splitOff(2, "LSX-C")).as("BR-PRD-11")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> parent.splitOff(10, "LSX-C")).as("everything: a conversion, not a split")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> parent.splitOff(0, "LSX-C")).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        ProductionOrder.Transition split = parent.splitOff(4, "LSX-C");
        assertThat(split.from()).isEqualTo(split.to()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(parent.work().plannedQuantity()).isEqualTo(6);
        ProductionOrder child = ProductionOrder.subcontractedChild(UUID.randomUUID(), "LSX-C", parent, 4, null,
                new ProductionOrder.Subcontract(ProductionOrder.SubcontractMethod.FULL_SERVICE, UUID.randomUUID(),
                        java.math.BigDecimal.valueOf(1500), "VND", null));
        assertThat(child.status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(child.mode()).isEqualTo(ProductionOrder.Mode.SUBCONTRACTED);
        assertThat(child.parentId()).isEqualTo(parent.id());
        assertThat(child.prepressCheckedAt()).isEqualTo(parent.prepressCheckedAt());
        assertThat(child.salesOrderLineId()).isEqualTo(parent.salesOrderLineId());
        assertThatThrownBy(() -> child.splitOff(1, "LSX-D")).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> child.issueBlanks(1)).as("its blanks go out with it")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);

        assertThat(child.sendToSubcontractor().to()).isEqualTo(ProductionOrder.Status.SUBCONTRACTED);
        assertThat(child.receivedFromSubcontractor(3).to()).isEqualTo(ProductionOrder.Status.QC);
        child.receivedFromSubcontractor(1);
        assertThat(child.awaitingQc()).isEqualTo(4);

        ProductionOrder replay = forLine(sample, work);
        assertThatThrownBy(() -> parent.requireSameWorkAs(replay)).as("6 is not what the line asked for")
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.PRODUCTION_SOURCE_CONFLICT);
        assertThatCode(() -> parent.requireSameWorkAs(replay, 4)).as("6 + 4 split off is")
                .doesNotThrowAnyException();
    }
}
