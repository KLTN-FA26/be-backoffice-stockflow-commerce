package com.stockflow.production.internal.domain;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.id.Identifiers;

import java.util.Objects;
import java.util.UUID;

/** Creation foundation only; prepress, stock issue, printing and QC belong to later stories. */
public final class ProductionOrder {
    public enum Type { SAMPLE, ORDER }
    public enum Status { PENDING_PREPRESS, READY, MATERIAL_ISSUED, PRINTING, QC, COMPLETED, ON_HOLD, CANCELLED }

    /** ORDER uses orderLineId as the source, independent of the delivery or release event. */
    public record Source(Type type, UUID id) {
        public Source {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
        }
    }

    public record Specification(Source source, UUID customerId, UUID warehouseId,
                                UUID orderId, String orderNumber,
                                SampleSpecification print, UUID approvedSampleId) {
        public Specification {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(print, "print");
            if (source.type() == Type.ORDER) {
                Objects.requireNonNull(orderId, "orderId");
                Objects.requireNonNull(approvedSampleId, "approvedSampleId");
                if (orderNumber == null || orderNumber.isBlank()) {
                    throw new IllegalArgumentException("Order number is required");
                }
            } else if (orderId != null || orderNumber != null || approvedSampleId != null) {
                throw new IllegalArgumentException("A sample production order has no sales order or approval");
            }
        }
    }

    private final UUID id;
    private final Specification specification;
    private final Status status;

    private ProductionOrder(Specification specification) {
        this(Identifiers.newId(), specification, Status.PENDING_PREPRESS);
    }

    private ProductionOrder(UUID id, Specification specification, Status status) {
        this.id = Objects.requireNonNull(id, "id");
        this.specification = Objects.requireNonNull(specification, "specification");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static ProductionOrder pending(Specification specification) {
        return new ProductionOrder(specification);
    }

    /** Replays retain the winner's lifecycle state; they must not reset a completed job to pending. */
    public static ProductionOrder restore(UUID id, Specification specification, Status status) {
        return new ProductionOrder(id, specification, status);
    }

    /** A replay must never silently change the material, quantity, design or warehouse. */
    public void requireSameSpecification(Specification expected) {
        if (!specification.equals(expected)) {
            throw new ConflictException("Production source was already created with different content");
        }
    }

    public UUID id() { return id; }
    public Specification specification() { return specification; }
    public Source source() { return specification.source(); }
    public Status status() { return status; }
}
