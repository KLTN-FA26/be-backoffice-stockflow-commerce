package com.stockflow.order.internal.domain;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A production order's life (4A-2, kltn-docs 17 §5.3). The edges are also rows of
 * {@code ordering.production_order_status_transition}; a test holds the two to each other.
 */
public enum ProductionOrderStatus {
    DRAFT,
    PENDING_APPROVAL,
    SENT,
    ACCEPTED,
    SAMPLE_PENDING,
    IN_PRODUCTION,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED,
    CLOSED;

    /** 17 §5.3: the moves each status allows. */
    public Set<ProductionOrderStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(PENDING_APPROVAL, CANCELLED);
            case PENDING_APPROVAL -> EnumSet.of(SENT, DRAFT, CANCELLED);
            case SENT -> EnumSet.of(ACCEPTED, IN_PRODUCTION, CANCELLED);
            case ACCEPTED -> EnumSet.of(SAMPLE_PENDING, IN_PRODUCTION, CANCELLED);
            case SAMPLE_PENDING -> EnumSet.of(IN_PRODUCTION, CANCELLED);
            case IN_PRODUCTION -> EnumSet.of(PARTIALLY_RECEIVED, RECEIVED, CLOSED);
            case PARTIALLY_RECEIVED -> EnumSet.of(RECEIVED, CLOSED);
            case RECEIVED, CANCELLED, CLOSED -> EnumSet.noneOf(ProductionOrderStatus.class);
        };
    }

    public boolean canTransitionTo(ProductionOrderStatus target) {
        return next().contains(target);
    }

    /** BR-06: cancelled only before mass printing. */
    public boolean cancellable() {
        return canTransitionTo(CANCELLED);
    }

    /** Still making or to make goods: what it has not delivered yet counts as on its way. */
    public boolean open() {
        return this != RECEIVED && this != CANCELLED && this != CLOSED;
    }

    /** Every edge as "FROM>TO", to compare with the transition table. */
    public static Set<String> edges() {
        Set<String> edges = new LinkedHashSet<>();
        for (ProductionOrderStatus from : values()) {
            for (ProductionOrderStatus to : from.next()) {
                edges.add(from.name() + ">" + to.name());
            }
        }
        return edges;
    }
}
