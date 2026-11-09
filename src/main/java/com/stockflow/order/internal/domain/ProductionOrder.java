package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a producer makes for one delivery batch of a confirmed order of a contract (4A-2, kltn-docs 17 §4.3, §5.3;
 * BR-05, BR-06, BR-23, BR-24, BR-26). The Production Coordinator draws it up for what the batch lacks at the order's
 * shipping warehouse - the receiving warehouse - and submits it; the Sales Manager, never the one who drew it up,
 * approves it, which sends it, or returns it with a reason. It is cancelled before mass printing, and closed - short,
 * or before anything came - after it started.
 *
 * <p>Every change of status is kept with who, when and why ({@link #pullChanges}). The database holds the same rules
 * ({@code ordering.check_production_order}, the CHECKs of {@code production_order}).</p>
 */
public final class ProductionOrder {

    /** The longest reason or note kept. */
    public static final int TEXT_MAX = 500;

    /**
     * One line: the order line's SKU, in whole cartons of its standard count, and the approved design it is printed to
     * with the checksum of its print files (BR-05).
     */
    public record Line(UUID id, UUID orderLineId, String sku, int cartons, int unitsPerCarton, UUID designSnapshotId,
                       String printFileChecksum, String packingRequirements, int receivedCartons) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(designSnapshotId, "designSnapshotId");
            Objects.requireNonNull(printFileChecksum, "printFileChecksum");
            if (cartons < 1 || unitsPerCarton < 1) {
                throw new IllegalArgumentException("A line makes whole cartons of a standard count");
            }
            if (receivedCartons < 0 || receivedCartons > cartons) {
                throw new IllegalArgumentException("Received cartons are between none and all of them");
            }
        }

        public int totalUnits() {
            return cartons * unitsPerCarton;
        }

        Line withCartons(int newCartons, String packing) {
            return new Line(id, orderLineId, sku, newCartons, unitsPerCarton, designSnapshotId, printFileChecksum,
                    packing, receivedCartons);
        }
    }

    /** A change of status, to be kept. */
    public record Change(ProductionOrderStatus from, ProductionOrderStatus to, String reason, UUID by, Instant at) {
    }

    /** What the coordinator may change while it is a draft (B33: the need-by date too). */
    public record Draft(UUID producerId, LocalDate needByDate, BigDecimal unitPrice, String note,
                        boolean sampleApprovalRequired, String sampleWaiverReason,
                        Map<UUID, LineEdit> lines) {
    }

    /** A line's new cartons and packing requirements, by order line. */
    public record LineEdit(int cartons, String packingRequirements) {
    }

    private final UUID id;
    private final String code;
    private final UUID salesOrderId;
    private final UUID deliveryBatchId;
    private final UUID customerId;
    private UUID producerId;
    private UUID receivingWarehouseId;
    private LocalDate needByDate;
    private ProductionOrderStatus status;
    private boolean sampleApprovalRequired;
    private final boolean sampleRequiredByDefault;
    private String sampleWaiverReason;
    private BigDecimal unitPrice;
    private final String currency;
    private String note;
    private final UUID drawnUpBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private String returnedReason;
    private Instant sentAt;
    private Instant productionStartedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;
    private String cancellationReason;
    private UUID closedBy;
    private Instant closedAt;
    private String closingReason;
    private List<Line> lines;
    private final long version;
    private final List<Change> changes = new ArrayList<>();

    /** Restores one as stored. */
    public ProductionOrder(UUID id, String code, UUID salesOrderId, UUID deliveryBatchId, UUID customerId,
                           UUID producerId, UUID receivingWarehouseId, LocalDate needByDate,
                           ProductionOrderStatus status, boolean sampleApprovalRequired,
                           boolean sampleRequiredByDefault, String sampleWaiverReason, BigDecimal unitPrice,
                           String currency, String note, UUID drawnUpBy, Instant submittedAt, UUID approvedBy,
                           Instant approvedAt, String returnedReason, Instant sentAt, Instant productionStartedAt,
                           UUID cancelledBy, Instant cancelledAt, String cancellationReason, UUID closedBy,
                           Instant closedAt, String closingReason, List<Line> lines, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.salesOrderId = Objects.requireNonNull(salesOrderId, "salesOrderId");
        this.deliveryBatchId = Objects.requireNonNull(deliveryBatchId, "deliveryBatchId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.producerId = Objects.requireNonNull(producerId, "producerId");
        this.receivingWarehouseId = Objects.requireNonNull(receivingWarehouseId, "receivingWarehouseId");
        this.needByDate = Objects.requireNonNull(needByDate, "needByDate");
        this.status = Objects.requireNonNull(status, "status");
        this.sampleApprovalRequired = sampleApprovalRequired;
        this.sampleRequiredByDefault = sampleRequiredByDefault;
        this.sampleWaiverReason = sampleWaiverReason;
        this.unitPrice = unitPrice;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.note = note;
        this.drawnUpBy = Objects.requireNonNull(drawnUpBy, "drawnUpBy");
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.returnedReason = returnedReason;
        this.sentAt = sentAt;
        this.productionStartedAt = productionStartedAt;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
        this.cancellationReason = cancellationReason;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.closingReason = closingReason;
        this.lines = new ArrayList<>(lines);
        this.version = version;
    }

    /**
     * A DRAFT for what a delivery batch lacks (17 §4.3 step 6): the need-by date is the batch's window start (BR-26); a
     * sample is required by default when one of its SKUs was never produced before (BR-24).
     *
     * @throws BusinessException {@code VALIDATION_FAILED} with no line
     */
    public static ProductionOrder drawUp(String code, UUID salesOrderId, UUID deliveryBatchId, UUID customerId,
                                         UUID producerId, UUID receivingWarehouseId, LocalDate needByDate,
                                         boolean sampleRequiredByDefault, List<Line> lines, String currency, UUID by,
                                         Instant at) {
        if (lines == null || lines.isEmpty()) {
            throw invalid("A production order makes at least one line");
        }
        ProductionOrder order = new ProductionOrder(Identifiers.newId(), code, salesOrderId, deliveryBatchId,
                customerId, producerId, receivingWarehouseId, needByDate, ProductionOrderStatus.DRAFT,
                sampleRequiredByDefault, sampleRequiredByDefault, null, null, currency, null, by, null, null, null,
                null, null, null, null, null, null, null, null, null, lines, 0L);
        order.changes.add(new Change(null, ProductionOrderStatus.DRAFT, null, Objects.requireNonNull(by, "by"), at));
        return order;
    }

    /**
     * The coordinator's changes to a draft (B33): producer, need-by date, price, note, the sample flag - turned off when
     * it is on by default, with a reason (BR-24) - and the lines' cartons and packing requirements. {@code maxCartons}
     * says, per order line, how many cartons the line may make at most (what the batch lacks).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID} when not a draft;
     *         {@code PRODUCTION_ORDER_EXCEEDS_SHORTAGE}; {@code VALIDATION_FAILED}
     */
    public void edit(Draft draft, Map<UUID, Integer> maxCartons) {
        requireStatus(ProductionOrderStatus.DRAFT, "edited");
        Objects.requireNonNull(draft, "draft");
        String waiver = text(draft.sampleWaiverReason());
        if (!draft.sampleApprovalRequired() && sampleRequiredByDefault && waiver == null) {
            throw invalid("A SKU is produced for the first time: say why its print sample is not approved (BR-24)");
        }
        if (draft.unitPrice() != null && draft.unitPrice().signum() < 0) {
            throw invalid("The unit price is not negative");
        }
        List<Line> edited = new ArrayList<>();
        for (Line line : lines) {
            LineEdit change = draft.lines() == null ? null : draft.lines().get(line.orderLineId());
            if (change == null) {
                edited.add(line);
                continue;
            }
            Integer max = maxCartons.get(line.orderLineId());
            if (change.cartons() < 1) {
                throw invalid("A line makes at least one carton");
            }
            if (max != null && change.cartons() > max) {
                throw new BusinessException(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE, ("%s: the batch lacks %d "
                        + "carton(s), not %d").formatted(line.sku(), max, change.cartons()));
            }
            edited.add(line.withCartons(change.cartons(), text(change.packingRequirements())));
        }
        if (draft.lines() != null && draft.lines().keySet().stream()
                .anyMatch(key -> lines.stream().noneMatch(line -> line.orderLineId().equals(key)))) {
            throw invalid("Only the production order's own lines are edited");
        }
        this.producerId = Objects.requireNonNull(draft.producerId(), "producerId");
        this.needByDate = Objects.requireNonNull(draft.needByDate(), "needByDate");
        this.unitPrice = draft.unitPrice();
        this.note = text(draft.note());
        this.sampleApprovalRequired = draft.sampleApprovalRequired();
        this.sampleWaiverReason = draft.sampleApprovalRequired() ? null : waiver;
        this.lines = edited;
    }

    /** DRAFT → PENDING_APPROVAL (17 §4.3 step 7). */
    public void submit(UUID by, Instant at) {
        move(ProductionOrderStatus.PENDING_APPROVAL, null, by, at, "submitted");
        this.submittedAt = at;
    }

    /**
     * Approved, so sent (17 §4.3 step 7, BR-23): never by the one who drew it up.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_SELF_APPROVAL}; {@code PRODUCTION_ORDER_STATUS_INVALID}
     */
    public void approve(UUID by, Instant at) {
        Objects.requireNonNull(by, "by");
        if (by.equals(drawnUpBy)) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_SELF_APPROVAL, ("Production order %s was drawn up by "
                    + "you: someone else approves it (BR-23)").formatted(code));
        }
        move(ProductionOrderStatus.SENT, null, by, at, "approved");
        this.approvedBy = by;
        this.approvedAt = at;
        this.sentAt = at;
    }

    /** Returned to the coordinator with a reason (BR-23): PENDING_APPROVAL → DRAFT. */
    public void returnToDraft(String reason, UUID by, Instant at) {
        String why = required(reason, "Returning a production order says why (BR-23)");
        move(ProductionOrderStatus.DRAFT, why, by, at, "returned");
        this.returnedReason = why;
    }

    /**
     * Cancelled before mass printing, with a reason (BR-06): from DRAFT, PENDING_APPROVAL, SENT, ACCEPTED or
     * SAMPLE_PENDING.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID} once mass printing started
     */
    public void cancel(String reason, UUID by, Instant at) {
        String why = required(reason, "Cancelling a production order says why (BR-06)");
        move(ProductionOrderStatus.CANCELLED, why, by, at, "cancelled");
        this.cancelledBy = by;
        this.cancelledAt = at;
        this.cancellationReason = why;
    }

    /**
     * Closed with a reason (BR-06, 17 §4.3 step 12): IN_PRODUCTION when the producer stopped before delivering anything,
     * PARTIALLY_RECEIVED when it will not deliver the rest.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID} from any other status
     */
    public void close(String reason, UUID by, Instant at) {
        String why = required(reason, "Closing a production order says why (BR-06)");
        move(ProductionOrderStatus.CLOSED, why, by, at, "closed");
        this.closedBy = by;
        this.closedAt = at;
        this.closingReason = why;
    }

    /**
     * The order's shipping warehouse changed (17 §4.2 step 2): a production order not yet sent is received at the new
     * one.
     *
     * @return whether it moved
     */
    public boolean receiveAt(UUID warehouseId) {
        if (status != ProductionOrderStatus.DRAFT && status != ProductionOrderStatus.PENDING_APPROVAL) {
            return false;
        }
        boolean moved = !warehouseId.equals(receivingWarehouseId);
        this.receivingWarehouseId = warehouseId;
        return moved;
    }

    /** What it has still to deliver of an order line, in cartons: nothing once it is received, cancelled or closed. */
    public int outstandingCartons(UUID orderLineId) {
        if (!status.open()) {
            return 0;
        }
        return lines.stream().filter(line -> line.orderLineId().equals(orderLineId))
                .mapToInt(line -> line.cartons() - line.receivedCartons()).sum();
    }

    /** Sent to the producer and not finished: it counts for an order waiting for stock (17 BR-04). */
    public boolean sentAndOpen() {
        return status.open() && status != ProductionOrderStatus.DRAFT
                && status != ProductionOrderStatus.PENDING_APPROVAL;
    }

    /** The changes of status since it was read, to be kept; each is handed over once. */
    public List<Change> pullChanges() {
        List<Change> pulled = List.copyOf(changes);
        changes.clear();
        return pulled;
    }

    private void move(ProductionOrderStatus target, String reason, UUID by, Instant at, String what) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID,
                    "Production order %s is %s and cannot be %s".formatted(code, status, what));
        }
        changes.add(new Change(status, target, reason, Objects.requireNonNull(by, "by"),
                Objects.requireNonNull(at, "at")));
        this.status = target;
    }

    private void requireStatus(ProductionOrderStatus wanted, String what) {
        if (status != wanted) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID,
                    "Production order %s is %s and cannot be %s".formatted(code, status, what));
        }
    }

    private static String required(String value, String message) {
        String text = text(value);
        if (text == null) {
            throw invalid(message);
        }
        return text;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > TEXT_MAX) {
            throw invalid("A reason or note is at most %d characters".formatted(TEXT_MAX));
        }
        return trimmed;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID salesOrderId() { return salesOrderId; }
    public UUID deliveryBatchId() { return deliveryBatchId; }
    public UUID customerId() { return customerId; }
    public UUID producerId() { return producerId; }
    public UUID receivingWarehouseId() { return receivingWarehouseId; }
    public LocalDate needByDate() { return needByDate; }
    public ProductionOrderStatus status() { return status; }
    public boolean sampleApprovalRequired() { return sampleApprovalRequired; }
    public boolean sampleRequiredByDefault() { return sampleRequiredByDefault; }
    public String sampleWaiverReason() { return sampleWaiverReason; }
    public BigDecimal unitPrice() { return unitPrice; }
    public String currency() { return currency; }
    public String note() { return note; }
    public UUID drawnUpBy() { return drawnUpBy; }
    public Instant submittedAt() { return submittedAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String returnedReason() { return returnedReason; }
    public Instant sentAt() { return sentAt; }
    public Instant productionStartedAt() { return productionStartedAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public Instant cancelledAt() { return cancelledAt; }
    public String cancellationReason() { return cancellationReason; }
    public UUID closedBy() { return closedBy; }
    public Instant closedAt() { return closedAt; }
    public String closingReason() { return closingReason; }
    public List<Line> lines() { return Collections.unmodifiableList(lines); }
    public long version() { return version; }
}
