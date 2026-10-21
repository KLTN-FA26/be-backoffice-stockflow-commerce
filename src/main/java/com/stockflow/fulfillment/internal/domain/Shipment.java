package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>A shipment</b>: the sealed parcels of one order that leave together (kltn-docs 09 §4.1 step 1, 08 step 9). One
 * order may have several (split shipments, G4-Q3; a mixed order's ready stock lines first, decision 2026-10-21), but a
 * parcel belongs to one order only (08 BR-04).
 *
 * <p>Born READY_TO_DISPATCH when packing completes (G4-5); the handover (G4-2a) and the outcomes the Order Coordinator
 * records (09 §4.3, BR-02) move it on through {@link ShipmentStatus}. Every step names who took it and when, and is
 * kept as a timeline row ({@link #pullRecorded}); a step to the status the shipment already has changes nothing, so a
 * retried call is harmless.</p>
 *
 * <p>Pure Java: no Spring, no JPA ({@code ArchitectureTest.domainDoesNotDependOnFrameworks}).</p>
 */
public final class Shipment {

    /** One step of the timeline, as {@code fulfillment.shipment_event} keeps it. */
    public record Step(ShipmentStatus status, Instant at, UUID by, String note) { }

    /** Everything a shipment row holds; how the aggregate is read back. */
    public record Snapshot(UUID id, String code, UUID orderId, UUID packId, UUID warehouseId, UUID requestId,
                           DeliveryMode deliveryMode, int packageCount, BigDecimal codAmount, boolean partial,
                           String partialNote, ShipmentStatus status, Instant readyAt, UUID readyBy,
                           Instant handedOverAt, UUID handedOverBy, Instant outcomeAt, UUID outcomeBy,
                           DeliveryFailureReason failureReason, String failureNote, Instant cancelledAt,
                           String cancelReason) { }

    private final UUID id;
    private final String code;
    private final UUID orderId;
    private final UUID packId;
    private final UUID warehouseId;
    private final UUID requestId;
    private final DeliveryMode deliveryMode;
    private final int packageCount;
    private BigDecimal codAmount;
    private final boolean partial;
    private final String partialNote;
    private ShipmentStatus status;
    private final Instant readyAt;
    private final UUID readyBy;
    private Instant handedOverAt;
    private UUID handedOverBy;
    private Instant outcomeAt;
    private UUID outcomeBy;
    private DeliveryFailureReason failureReason;
    private String failureNote;
    private Instant cancelledAt;
    private String cancelReason;
    private final List<Step> recorded = new ArrayList<>();

    private Shipment(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.code = Objects.requireNonNull(s.code(), "code");
        this.orderId = Objects.requireNonNull(s.orderId(), "orderId");
        this.packId = s.packId();
        this.warehouseId = Objects.requireNonNull(s.warehouseId(), "warehouseId");
        this.requestId = s.requestId();
        this.deliveryMode = s.deliveryMode() == null ? DeliveryMode.PARCEL : s.deliveryMode();
        this.packageCount = s.packageCount();
        this.codAmount = s.codAmount() == null ? BigDecimal.ZERO : s.codAmount();
        this.partial = s.partial();
        this.partialNote = s.partialNote();
        this.status = Objects.requireNonNull(s.status(), "status");
        this.readyAt = Objects.requireNonNull(s.readyAt(), "readyAt");
        this.readyBy = s.readyBy();
        this.handedOverAt = s.handedOverAt();
        this.handedOverBy = s.handedOverBy();
        this.outcomeAt = s.outcomeAt();
        this.outcomeBy = s.outcomeBy();
        this.failureReason = s.failureReason();
        this.failureNote = s.failureNote();
        this.cancelledAt = s.cancelledAt();
        this.cancelReason = s.cancelReason();
    }

    /**
     * Packing completed (08 step 7-9): the sealed parcels wait at dispatch, READY_TO_DISPATCH (09 §4.1 step 1).
     *
     * @param codAmount   what the carrier collects for it (09 BR-04, the label), never below zero
     * @param partialNote why only part of the order goes; null when it carries everything still to ship
     */
    public static Shipment ready(UUID id, String code, UUID orderId, UUID packId, UUID warehouseId, UUID requestId,
                                 DeliveryMode deliveryMode, int packageCount, BigDecimal codAmount, String partialNote,
                                 UUID by, Instant at) {
        if (packageCount < 1) {
            throw new IllegalArgumentException("A shipment carries at least one package");
        }
        if (codAmount != null && codAmount.signum() < 0) {
            throw new IllegalArgumentException("A COD amount is never negative");
        }
        Objects.requireNonNull(by, "by");
        Shipment shipment = new Shipment(new Snapshot(id, code, orderId, packId, warehouseId, requestId, deliveryMode,
                packageCount, codAmount, partialNote != null, partialNote, ShipmentStatus.READY_TO_DISPATCH, at, by,
                null, null, null, null, null, null, null, null));
        shipment.recorded.add(new Step(ShipmentStatus.READY_TO_DISPATCH, at, by, partialNote));
        return shipment;
    }

    public static Shipment restore(Snapshot snapshot) {
        return new Shipment(snapshot);
    }

    /** Handed to the carrier with a signed manifest (09 §4.1 step 3, §4.2 step 5). */
    public boolean handOver(UUID by, Instant at) {
        if (!move(ShipmentStatus.HANDED_OVER, by)) {
            return false;
        }
        handedOverAt = at;
        handedOverBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    /** The carrier reported it delivered, on the first try or a retry (09 §4.3 step 1-2). */
    public boolean recordDelivered(UUID by, Instant at) {
        if (!move(ShipmentStatus.DELIVERED, by)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    /** The carrier could not deliver it (09 §4.3 step 2); OTHER needs a note. */
    public boolean recordFailed(DeliveryFailureReason reason, String note, UUID by, Instant at) {
        Objects.requireNonNull(reason, "reason");
        if (reason == DeliveryFailureReason.OTHER && (note == null || note.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failure for another reason says what it was");
        }
        if (!move(ShipmentStatus.DELIVERY_FAILED, by)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        failureReason = reason;
        failureNote = note;
        recorded.add(new Step(status, at, by, note));
        return true;
    }

    /** The parcels came back to the warehouse through a returns receipt (09 §4.3 step 3, BR-06). */
    public boolean recordReturned(UUID by, Instant at) {
        if (!move(ShipmentStatus.RETURNED, by)) {
            return false;
        }
        outcomeAt = at;
        outcomeBy = by;
        recorded.add(new Step(status, at, by, null));
        return true;
    }

    /** Before the handover only: the order was cancelled or the parcels are taken apart again (08 BR-05, BR-06). */
    public boolean cancel(String reason, UUID by, Instant at) {
        if (status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (!status.canTransitionTo(ShipmentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; only one waiting for dispatch is cancelled".formatted(code, status));
        }
        status = ShipmentStatus.CANCELLED;
        cancelledAt = at;
        cancelReason = reason;
        recorded.add(new Step(status, at, by, reason));
        return true;
    }

    /** The COD amount fixed later (the handover, 15 BR-08) replaces what the label was printed with. */
    public void fixCodAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("A COD amount is never negative");
        }
        this.codAmount = amount;
    }

    private boolean move(ShipmentStatus target, UUID by) {
        if (status == target) {
            return false;
        }
        Objects.requireNonNull(by, "who recorded it (09 BR-02)");
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                    "Shipment %s is %s; it cannot become %s".formatted(code, status, target));
        }
        status = target;
        return true;
    }

    /** The timeline steps taken since it was read, oldest first; cleared by the call. */
    public List<Step> pullRecorded() {
        List<Step> copy = List.copyOf(recorded);
        recorded.clear();
        return copy;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, code, orderId, packId, warehouseId, requestId, deliveryMode, packageCount, codAmount,
                partial, partialNote, status, readyAt, readyBy, handedOverAt, handedOverBy, outcomeAt, outcomeBy,
                failureReason, failureNote, cancelledAt, cancelReason);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID orderId() { return orderId; }
    public ShipmentStatus status() { return status; }
    public int packageCount() { return packageCount; }
    public BigDecimal codAmount() { return codAmount; }
    public boolean partial() { return partial; }
}
