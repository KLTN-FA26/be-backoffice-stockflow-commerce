package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

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
    private final Status status;
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

    /** The id the uniqueness of this order hangs on: the sample request or the order line. */
    public UUID sourceId() {
        return type == Type.SAMPLE ? sampleRequestId : salesOrderLineId;
    }
}
