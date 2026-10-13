package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A production order (LSX, 03-state-machines §16): born from a submitted sample round ({@code SAMPLE}) or
 * one released order line ({@code ORDER}), always in house and in {@code PENDING_PREPRESS} (SCRUM-395),
 * then taken through the workshop (SCRUM-396..401): prepress, blanks issued, printing, QC, completion or
 * a reprint round, holds, cancellation.
 *
 * <p><b>Quantities</b> ({@code ck_production_order_quantities}): {@code issued} blanks reached the print
 * shop; {@code printed} of them were printed, each either {@code good} or {@code scrap} (BR-PRD-04);
 * {@code passed} of the good ones passed QC. A unit QC fails moves from good to scrap. The blanks still
 * needed (BR-PRD-03) are the good units planned, less the good units made, less the blanks issued and not
 * yet printed — so a reprint round asks for exactly the shortfall.</p>
 *
 * <p><b>Subcontracting</b> (SCRUM-433/441, 03 §16): part of an ORDER's unissued quantity is split off into a
 * child (mode SUBCONTRACTED, {@code parentId}) printed by an outside print shop; the parent keeps its status
 * and loses the quantity. The child goes READY → SUBCONTRACTED (sent) → QC (received back).</p>
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

    /** {@code ck_production_order_mode}: printed in the shop, or by a subcontractor. */
    public enum Mode { IN_HOUSE, SUBCONTRACTED }

    /** We send the blanks, or the subcontractor sources them and we buy finished goods (433). */
    public enum SubcontractMethod { SUPPLIED_BLANKS, FULL_SERVICE }

    /** The terms of a subcontracted child ({@code ck_production_order_subcontract_fields}). */
    public record Subcontract(SubcontractMethod method, UUID subcontractorId, java.math.BigDecimal unitPrice,
                              String currency, UUID purchaseOrderId) {
        public Subcontract {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(subcontractorId, "subcontractorId");
            Objects.requireNonNull(unitPrice, "unitPrice");
            if (unitPrice.signum() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A subcontract price is not negative");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A subcontract price names its currency");
            }
        }

        public Subcontract withPurchaseOrder(UUID purchaseOrderId) {
            return new Subcontract(method, subcontractorId, unitPrice, currency, purchaseOrderId);
        }
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
    private Work work;
    private Mode mode = Mode.IN_HOUSE;
    private UUID parentId;
    private Subcontract subcontract;
    private int issued;
    private int printed;
    private int good;
    private int scrap;
    private int passed;
    private Instant completedAt;
    private Instant cancelledAt;
    private String cancellationReason;

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

    /** Rehydration of the subcontracting columns, after {@link #restore}. */
    public ProductionOrder withSubcontract(Mode mode, UUID parentId, Subcontract subcontract) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.parentId = parentId;
        this.subcontract = subcontract;
        return this;
    }

    // ------------------------------------------------------------------ subcontracting (SCRUM-441)

    /** The parent states a split is taken from (03 §16; BR-PRD-12). */
    private static final java.util.EnumSet<Status> SPLITTABLE =
            java.util.EnumSet.of(Status.PENDING_PREPRESS, Status.READY, Status.ON_HOLD);

    /** Units planned and not yet issued to the print shop: what a split may take (BR-PRD-12). */
    public int unissued() {
        return Math.max(0, work.plannedQuantity() - issued);
    }

    /**
     * The parent's side of a split (BR-PRD-11, 12): an in-house ORDER in PENDING_PREPRESS, READY or ON_HOLD
     * gives up {@code quantity} of the units it has not issued yet, keeping its status. Splitting everything
     * would convert the order itself, which this does not do.
     */
    public Transition splitOff(int quantity, String childNumber) {
        if (type != Type.ORDER) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "A sample is always printed in house (BR-PRD-11)");
        }
        if (mode != Mode.IN_HOUSE || parentId != null) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is already subcontracted".formatted(orderNumber));
        }
        if (!SPLITTABLE.contains(status)) {
            throw transition("split", status);
        }
        if (quantity < 1 || quantity >= unissued()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A split takes more than 0 and fewer than the %d unit(s) not issued yet (BR-PRD-12)"
                            .formatted(unissued()));
        }
        work = new Work(work.warehouseId(), work.blankSku(), work.designSnapshotId(), work.designChecksum(),
                work.plannedQuantity() - quantity, work.dueDate());
        return new Transition(status, status, "Split %d to %s for subcontracting".formatted(quantity, childNumber));
    }

    /**
     * The child of a split: the same order line, design, approved sample and prepress result as its parent
     * (03 §16 "the child inherits the parent's prepress result"), {@code quantity} planned, its own due date.
     */
    public static ProductionOrder subcontractedChild(UUID id, String orderNumber, ProductionOrder parent, int quantity,
                                                     LocalDate dueDate, Subcontract terms) {
        Work w = new Work(parent.work.warehouseId(), parent.work.blankSku(), parent.work.designSnapshotId(),
                parent.work.designChecksum(), quantity, dueDate);
        boolean checked = parent.prepressCheckedAt != null;
        ProductionOrder child = new ProductionOrder(id, orderNumber, Type.ORDER,
                checked ? Status.READY : Status.PENDING_PREPRESS, null, parent.salesOrderId, parent.salesOrderLineId,
                parent.approvedSampleId, w);
        child.mode = Mode.SUBCONTRACTED;
        child.parentId = parent.id;
        child.subcontract = Objects.requireNonNull(terms, "terms");
        child.prepressCheckedBy = parent.prepressCheckedBy;
        child.prepressCheckedAt = parent.prepressCheckedAt;
        return child;
    }

    /** The subcontract PO raised for this child (SCRUM-434). */
    public void attachPurchaseOrder(UUID purchaseOrderId) {
        requireSubcontracted();
        subcontract = subcontract.withPurchaseOrder(Objects.requireNonNull(purchaseOrderId, "purchaseOrderId"));
    }

    /** READY → SUBCONTRACTED; the caller checked the PO is approved (BR-PRD-13). The design is locked from here. */
    public Transition sendToSubcontractor() {
        requireSubcontracted();
        requireStatus(Status.READY, "send out");
        status = Status.SUBCONTRACTED;
        return new Transition(Status.READY, Status.SUBCONTRACTED, "Sent to the subcontractor");
    }

    /**
     * The subcontractor's goods were received (G9): SUBCONTRACTED → QC with {@code quantity} more to inspect. A
     * further part of the same PO received while QC has not finished adds to what waits for it; one received after
     * a QC round left the child short (READY, something received before) is the rest of the work arriving.
     */
    public Transition receivedFromSubcontractor(int quantity) {
        requireSubcontracted();
        if (status != Status.SUBCONTRACTED && status != Status.QC && !(status == Status.READY && printed > 0)) {
            throw transition("receive the subcontracted goods of", Status.QC);
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("A receipt counts at least one unit");
        }
        Status from = status;
        printed += quantity;
        good += quantity;
        status = Status.QC;
        return new Transition(from, Status.QC, "Received %d from the subcontractor".formatted(quantity));
    }

    private void requireSubcontracted() {
        if (mode != Mode.SUBCONTRACTED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is printed in house".formatted(orderNumber));
        }
    }

    /** Rehydration of the quantities and the end states, after {@link #restore}. */
    public ProductionOrder withProgress(int issued, int printed, int good, int scrap, int passed, Instant completedAt,
                                        Instant cancelledAt, String cancellationReason) {
        this.issued = issued;
        this.printed = printed;
        this.good = good;
        this.scrap = scrap;
        this.passed = passed;
        this.completedAt = completedAt;
        this.cancelledAt = cancelledAt;
        this.cancellationReason = cancellationReason;
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

    // ------------------------------------------------------------------ blanks, printing, QC (SCRUM-397..399)

    /** Blanks the order still needs on the press (BR-PRD-03): planned − good − issued-but-unprinted. */
    public int outstandingBlanks() {
        return Math.max(0, work.plannedQuantity() - good - unprintedBlanks());
    }

    /** Blanks in the print shop not printed yet. */
    public int unprintedBlanks() {
        return issued - printed;
    }

    /** Good units printed and not yet through QC. */
    public int awaitingQc() {
        return good - passed;
    }

    /**
     * Blanks reached the print shop (SCRUM-397). Never more than still needed (BR-PRD-03). READY becomes
     * MATERIAL_ISSUED once nothing more is needed; until then the order stays READY for the next scan.
     */
    public Optional<Transition> issueBlanks(int quantity) {
        requireStatus(Status.READY, "issue blanks to");
        if (mode == Mode.SUBCONTRACTED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is printed by a subcontractor; its blanks go out with it".formatted(orderNumber));
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("An issue moves at least one blank");
        }
        if (quantity > outstandingBlanks()) {
            throw new BusinessException(ErrorCode.ISSUE_EXCEEDS_REQUIREMENT,
                    "Production order %s needs %d more blank(s); %d were offered (BR-PRD-03)"
                            .formatted(orderNumber, outstandingBlanks(), quantity));
        }
        issued += quantity;
        if (outstandingBlanks() > 0) {
            return Optional.empty();
        }
        status = Status.MATERIAL_ISSUED;
        return Optional.of(new Transition(Status.READY, Status.MATERIAL_ISSUED,
                "Blanks issued: %d unprinted in the print shop".formatted(unprintedBlanks())));
    }

    /** Printing starts (SCRUM-398). From here the design cannot change (BR-PRD-07). */
    public Transition startPrinting() {
        requireStatus(Status.MATERIAL_ISSUED, "start printing");
        status = Status.PRINTING;
        return new Transition(Status.MATERIAL_ISSUED, Status.PRINTING, "Printing started");
    }

    /**
     * The run's output (SCRUM-398, BR-PRD-04): {@code printedNow} units printed from the issued blanks, of
     * which {@code scrapNow} were spoilt; the rest go to QC. Blanks left unprinted stay issued for the next
     * round. A run whose every unit was spoilt has nothing for QC and goes straight back to READY.
     */
    public List<Transition> recordOutput(int printedNow, int scrapNow) {
        requireStatus(Status.PRINTING, "record the output of");
        if (printedNow < 1 || scrapNow < 0 || scrapNow > printedNow) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A run prints at least one unit, and spoils no more than it printed");
        }
        if (printedNow > unprintedBlanks()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Only %d issued blank(s) are unprinted; %d cannot have been printed"
                            .formatted(unprintedBlanks(), printedNow));
        }
        printed += printedNow;
        scrap += scrapNow;
        good += printedNow - scrapNow;
        status = Status.QC;
        List<Transition> transitions = new ArrayList<>();
        transitions.add(new Transition(Status.PRINTING, Status.QC,
                "Printed %d, scrap %d".formatted(printedNow, scrapNow)));
        if (awaitingQc() == 0) {
            status = Status.READY;
            transitions.add(new Transition(Status.QC, Status.READY, "Nothing to inspect: every unit was spoilt"));
        }
        return transitions;
    }

    /**
     * A QC round (SCRUM-399, BR-PRD-05) inspects every good unit awaiting it. Failed units become scrap.
     * Enough good units: COMPLETED; short: READY for a reprint round. The only way out of QC.
     */
    public Transition recordQc(int passedNow, int failedNow, Instant at) {
        Objects.requireNonNull(at, "at");
        requireStatus(Status.QC, "record QC of");
        if (passedNow < 0 || failedNow < 0 || passedNow + failedNow != awaitingQc()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "QC inspects every printed unit awaiting it: %d, not %d".formatted(awaitingQc(),
                            passedNow + failedNow));
        }
        good -= failedNow;
        scrap += failedNow;
        passed += passedNow;
        if (good >= work.plannedQuantity()) {
            status = Status.COMPLETED;
            completedAt = at;
            return new Transition(Status.QC, Status.COMPLETED,
                    "QC passed %d, failed %d; %d good".formatted(passedNow, failedNow, good));
        }
        status = Status.READY;
        return new Transition(Status.QC, Status.READY, "QC passed %d, failed %d; %d more good unit(s) needed"
                .formatted(passedNow, failedNow, work.plannedQuantity() - good));
    }

    // ------------------------------------------------------------------ cancel and stop (SCRUM-401)

    private static final java.util.EnumSet<Status> CANCELLABLE =
            java.util.EnumSet.of(Status.PENDING_PREPRESS, Status.READY, Status.MATERIAL_ISSUED);

    /** The status that decides what may happen to a held order: the one the hold interrupted. */
    public Status effectiveStatus() {
        return status == Status.ON_HOLD ? statusBeforeHold : status;
    }

    /**
     * Cancel before printing (03 §16: PENDING_PREPRESS, READY, MATERIAL_ISSUED; also ON_HOLD from one of
     * those, stopped where it was). The caller puts the issued blanks back into stock.
     */
    public Transition cancel(String reason, Instant at) {
        String text = requireReason(reason);
        Objects.requireNonNull(at, "at");
        if (!CANCELLABLE.contains(effectiveStatus())) {
            throw transition("cancel", Status.CANCELLED);
        }
        return end(text, at);
    }

    /**
     * Stop once printing has started (decision Q2, 2026-10-12, BR-PRD-10): the unprinted part is not
     * printed; what was printed stays on the record. {@code printedNow}/{@code scrapNow} is the output of the
     * run being stopped, if any. Goods printed for a stopped order are sold to nobody else (Q3): the good
     * ones become scrap here, and the caller writes them off through an approved adjustment. A charge the
     * customer agreed to is Sales' business on the order, not the LSX's.
     *
     * @return the good units this stop turned into scrap
     */
    public int stop(int printedNow, int scrapNow, String reason, Instant at) {
        String text = requireReason(reason);
        Objects.requireNonNull(at, "at");
        Status effective = effectiveStatus();
        if (effective != Status.PRINTING && effective != Status.QC) {
            throw transition("stop", Status.CANCELLED);
        }
        if (printedNow != 0 || scrapNow != 0) {
            if (effective != Status.PRINTING) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a run in progress has output to record");
            }
            if (printedNow < 0 || scrapNow < 0 || scrapNow > printedNow || printedNow > unprintedBlanks()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "The stopped run printed between 0 and %d unit(s) and spoilt no more than it printed"
                                .formatted(unprintedBlanks()));
            }
            printed += printedNow;
            scrap += scrapNow;
            good += printedNow - scrapNow;
        }
        int unsold = good;
        scrap += good;
        good = 0;
        passed = 0;
        end(text, at);
        return unsold;
    }

    private Transition end(String reason, Instant at) {
        Status from = status;
        status = Status.CANCELLED;
        statusBeforeHold = null;
        holdReason = null;
        holdNote = null;
        cancelledAt = at;
        cancellationReason = reason;
        return new Transition(from, Status.CANCELLED, reason);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation says why");
        }
        String text = reason.strip();
        if (text.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation reason is at most 500 characters");
        }
        return text;
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
        requireSameWorkAs(requested, 0);
    }

    /**
     * The same, for a parent that gave {@code splitOff} units to subcontracted children since it was created:
     * its plan plus theirs is what the source asked for.
     */
    public void requireSameWorkAs(ProductionOrder requested, int splitOff) {
        Work asked = requested.work;
        Work original = new Work(work.warehouseId(), work.blankSku(), work.designSnapshotId(), work.designChecksum(),
                work.plannedQuantity() + splitOff, work.dueDate());
        if (type != requested.type
                || !Objects.equals(sampleRequestId, requested.sampleRequestId)
                || !Objects.equals(salesOrderId, requested.salesOrderId)
                || !Objects.equals(salesOrderLineId, requested.salesOrderLineId)
                || !Objects.equals(approvedSampleId, requested.approvedSampleId)
                || !original.equals(asked)) {
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
    public int issued() { return issued; }
    public int printed() { return printed; }
    public int good() { return good; }
    public int scrap() { return scrap; }
    public int passed() { return passed; }
    public Instant completedAt() { return completedAt; }
    public Mode mode() { return mode; }
    public UUID parentId() { return parentId; }
    public Subcontract subcontract() { return subcontract; }
    public Instant cancelledAt() { return cancelledAt; }
    public String cancellationReason() { return cancellationReason; }

    /** The id the uniqueness of this order hangs on: the sample request or the order line. */
    public UUID sourceId() {
        return type == Type.SAMPLE ? sampleRequestId : salesOrderLineId;
    }
}
