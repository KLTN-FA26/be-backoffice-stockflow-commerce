package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A production order (LSX) as it is born (SCRUM-395, 03-state-machines §16): from a submitted sample
 * round ({@code SAMPLE}) or from one released order line ({@code ORDER}), always in house and in
 * {@code PENDING_PREPRESS}.
 *
 * <p>What happens to it afterwards — prepress, blanks, printing, QC, hold, split for subcontracting —
 * belongs to SCRUM-396 onwards and is deliberately not modelled here: this class holds exactly what
 * creation decides and what a replay must agree with.</p>
 *
 * <p><b>One per source (BR-PRD-01).</b> The source is the sample request or the order line, never the
 * event that announced it: a redelivered release names the same line and must find the order already
 * there. {@link #requireSameWorkAs} is the replay check — a second announcement of the same source that
 * disagrees on what to print is a defect upstream, not a reason to print twice or to overwrite.</p>
 */
public final class ProductionOrder {

    public enum Type { SAMPLE, ORDER }

    /** {@code ck_production_order_status}; every value is readable even though only the first is created here. */
    public enum Status {
        PENDING_PREPRESS, READY, MATERIAL_ISSUED, PRINTING, SUBCONTRACTED, QC, ON_HOLD, COMPLETED, CANCELLED
    }

    /** {@code ck_production_order_hold_reason}: why work stopped (Jira 396, 401). */
    public enum HoldReason { FILE_REJECTED, BLANKS_SHORT, MACHINE_DOWN, WAITING_CUSTOMER, SUBCONTRACTOR_LATE, OTHER }

    /** The states a hold can interrupt and a resume returns to ({@code ck_production_order_status_before_hold}). */
    private static final java.util.EnumSet<Status> HOLDABLE = java.util.EnumSet.of(Status.PENDING_PREPRESS,
            Status.READY, Status.MATERIAL_ISSUED, Status.PRINTING, Status.SUBCONTRACTED);

    /** A status change the service records on {@code production_order_status_history}. */
    public record Transition(Status from, Status to, String reason) {
    }

    /** What to print, where, how many good units, by when. Identical on a replay or it is refused. */
    public record Work(UUID warehouseId, Sku blankSku, UUID designSnapshotId, String designChecksum,
                       int plannedQuantity, LocalDate dueDate) {
        public Work {
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(blankSku, "blankSku");
            Objects.requireNonNull(designSnapshotId, "designSnapshotId");
            if (designChecksum == null || designChecksum.isBlank() || designChecksum.length() > 128) {
                throw new IllegalArgumentException("A production order prints against a design checksum");
            }
            if (plannedQuantity < 1) {
                throw new IllegalArgumentException("A production order plans at least one good unit");
            }
        }
    }

    private final UUID id;
    private final String orderNumber;
    private final Type type;
    private Status status;
    private Status statusBeforeHold;
    private HoldReason holdReason;
    private String holdNote;
    private UUID prepressCheckedBy;
    private Instant prepressCheckedAt;
    private final UUID sampleRequestId;
    private final UUID salesOrderId;
    private final UUID salesOrderLineId;
    private final UUID approvedSampleId;
    private final Work work;

    private ProductionOrder(UUID id, String orderNumber, Type type, Status status, UUID sampleRequestId,
                            UUID salesOrderId, UUID salesOrderLineId, UUID approvedSampleId, Work work) {
        this.id = Objects.requireNonNull(id, "id");
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("A production order needs a number");
        }
        this.orderNumber = orderNumber;
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        // ck_production_order_source: the source matches the type.
        boolean sample = sampleRequestId != null && salesOrderId == null && salesOrderLineId == null
                && approvedSampleId == null;
        boolean order = sampleRequestId == null && salesOrderId != null && salesOrderLineId != null;
        if (type == Type.SAMPLE ? !sample : !order) {
            throw new IllegalArgumentException("The source of a " + type + " production order does not match its type");
        }
        this.sampleRequestId = sampleRequestId;
        this.salesOrderId = salesOrderId;
        this.salesOrderLineId = salesOrderLineId;
        this.approvedSampleId = approvedSampleId;
        this.work = Objects.requireNonNull(work, "work");
    }

    /** The workshop's copy of a submitted sample round, printed against the confirmed design file. */
    public static ProductionOrder forSample(UUID id, String orderNumber, UUID sampleRequestId, Work work) {
        return new ProductionOrder(id, orderNumber, Type.SAMPLE, Status.PENDING_PREPRESS, sampleRequestId,
                null, null, null, work);
    }

    /**
     * One released order line. {@code approvedSampleId} is the sample QC compares against (BR-PRD-05);
     * null only for a repeat design that was printed to completion before (BR-PRD-08).
     */
    public static ProductionOrder forOrderLine(UUID id, String orderNumber, UUID salesOrderId, UUID salesOrderLineId,
                                               UUID approvedSampleId, Work work) {
        return new ProductionOrder(id, orderNumber, Type.ORDER, Status.PENDING_PREPRESS, null, salesOrderId,
                salesOrderLineId, approvedSampleId, work);
    }

    public static ProductionOrder restore(UUID id, String orderNumber, Type type, Status status, UUID sampleRequestId,
                                          UUID salesOrderId, UUID salesOrderLineId, UUID approvedSampleId, Work work) {
        return new ProductionOrder(id, orderNumber, type, status, sampleRequestId, salesOrderId, salesOrderLineId,
                approvedSampleId, work);
    }

    /** Rehydration of the workflow columns, after {@link #restore}. */
    public ProductionOrder withWorkflow(Status beforeHold, HoldReason reason, String note, UUID checkedBy,
                                        Instant checkedAt) {
        this.statusBeforeHold = beforeHold;
        this.holdReason = reason;
        this.holdNote = note;
        this.prepressCheckedBy = checkedBy;
        this.prepressCheckedAt = checkedAt;
        return this;
    }

    /**
     * Prepress passed (BR-PRD-02): the print file was checked against the locked design. The caller verifies
     * the checksum before calling; this records who checked it and when, which every later state requires
     * ({@code ck_production_order_prepress}).
     */
    public Transition passPrepress(UUID by, Instant at) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(at, "at");
        requireStatus(Status.PENDING_PREPRESS, "pass prepress of");
        prepressCheckedBy = by;
        prepressCheckedAt = at;
        status = Status.READY;
        return new Transition(Status.PENDING_PREPRESS, Status.READY, "Prepress passed");
    }

    /**
     * Stop work, saying why. Allowed from every state before COMPLETED that the schema lets a hold remember;
     * QC is left by recording its result, not by a hold.
     */
    public Transition hold(HoldReason reason, String note, Instant at) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A hold note is at most 1000 characters");
        }
        if (reason == HoldReason.OTHER && text == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A hold for another reason must say what it is");
        }
        if (!HOLDABLE.contains(status)) {
            throw transition("hold", Status.ON_HOLD);
        }
        Status from = status;
        statusBeforeHold = status;
        holdReason = reason;
        holdNote = text;
        status = Status.ON_HOLD;
        return new Transition(from, Status.ON_HOLD, reason + (text == null ? "" : ": " + text));
    }

    /** Back to the state the hold interrupted (Jira 396: "resuming returns to the previous state"). */
    public Transition resume() {
        requireStatus(Status.ON_HOLD, "resume");
        Status back = statusBeforeHold;
        status = back;
        statusBeforeHold = null;
        holdReason = null;
        holdNote = null;
        return new Transition(Status.ON_HOLD, back, "Resumed");
    }

    private void requireStatus(Status expected, String verb) {
        if (status != expected) {
            throw transition(verb, expected);
        }
    }

    private BusinessException transition(String verb, Status target) {
        return new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                "Cannot %s production order %s: it is %s".formatted(verb, orderNumber, status));
    }

    /**
     * A replay of the same source must describe the same work. Status is not compared: a replay that
     * arrives after the order was printed must leave it as it is, not reset it.
     */
    public void requireSameWorkAs(ProductionOrder requested) {
        if (type != requested.type
                || !Objects.equals(sampleRequestId, requested.sampleRequestId)
                || !Objects.equals(salesOrderId, requested.salesOrderId)
                || !Objects.equals(salesOrderLineId, requested.salesOrderLineId)
                || !Objects.equals(approvedSampleId, requested.approvedSampleId)
                || !work.equals(requested.work)) {
            throw new BusinessException(ErrorCode.PRODUCTION_SOURCE_CONFLICT,
                    "Production order %s already exists for this %s with different content"
                            .formatted(orderNumber, type == Type.SAMPLE ? "sample request" : "order line"));
        }
    }

    public UUID id() { return id; }
    public String orderNumber() { return orderNumber; }
    public Type type() { return type; }
    public Status status() { return status; }
    public UUID sampleRequestId() { return sampleRequestId; }
    public UUID salesOrderId() { return salesOrderId; }
    public UUID salesOrderLineId() { return salesOrderLineId; }
    public UUID approvedSampleId() { return approvedSampleId; }
    public Work work() { return work; }
    public Status statusBeforeHold() { return statusBeforeHold; }
    public HoldReason holdReason() { return holdReason; }
    public String holdNote() { return holdNote; }
    public UUID prepressCheckedBy() { return prepressCheckedBy; }
    public Instant prepressCheckedAt() { return prepressCheckedAt; }

    /** The id the uniqueness of this order hangs on: the sample request or the order line. */
    public UUID sourceId() {
        return type == Type.SAMPLE ? sampleRequestId : salesOrderLineId;
    }
}
