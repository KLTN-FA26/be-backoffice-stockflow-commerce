package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link DeliveryFailureCase} (WF-05 fix plan, sections 2-3), and for the holds taken for a case:
 * the units of the returned shipment held for the order again. Every write is made under the order's row lock, which
 * the caller takes first (orders, then everything of the order).
 */
public interface DeliveryFailureCases {

    /** A hold taken for a case - a SKU reservation, or an allocation after release - and the line it stands for. */
    record Hold(UUID reservationId, UUID caseId, UUID orderLineId) {
    }

    DeliveryFailureCase save(DeliveryFailureCase failure);

    Optional<DeliveryFailureCase> find(UUID id);

    /** The case of a returned shipment, if its goods were received back. */
    Optional<DeliveryFailureCase> findByShipment(UUID shipmentId);

    /** The order's cases, oldest first. */
    List<DeliveryFailureCase> ofOrder(UUID orderId);

    /**
     * Cases in {@code status} (any when null), oldest first: what waits for Sales. {@code warehouses} not null keeps
     * the cases whose goods came back to one of them - empty keeps none (a caller limited to warehouses and assigned
     * none sees nothing, never everything).
     */
    List<DeliveryFailureCase> list(DeliveryFailureCase.Status status, Collection<UUID> warehouses, int limit);

    /**
     * The orders with a case that still wants its goods held - OPEN, or to be delivered again and not released yet -
     * oldest case first: the first served when stock is put away.
     */
    default List<UUID> ordersWantingGoodsHeld() {
        return ordersWantingGoodsHeld(null);
    }

    /**
     * The same, limited to cases whose goods that came back include one of {@code skus} (null: any SKU) - WF-05 review
     * F14: stock of one SKU locks only the orders whose returned goods it can serve.
     */
    List<UUID> ordersWantingGoodsHeld(Collection<String> skus);

    /**
     * Orders RETURNED whose every delivery failure case is closed and one of them CANCELLED, with no shipment
     * DELIVERED: nothing of the order was delivered and Sales cancelled what was not - they wait only for their
     * refunds. An order with goods delivered is RETURNED by a return request, which moves it (WF-05 review F2). Oldest decision first, the first
     * {@code skip} left out (the ones a run looked at and left as they were).
     */
    List<UUID> ordersReturnedByCancellation(int skip, int limit);

    /** Whether the order has a case its settlement waits for: any not closed. */
    boolean hasUndecided(UUID orderId);

    List<Hold> holdsOf(UUID caseId);

    /** The holds were taken for {@code caseId}'s units of {@code orderLineId}. */
    void recordHolds(UUID caseId, UUID orderLineId, Collection<UUID> reservationIds);

    /** The holds that replaced {@code replaced} (its allocations at release) stand for the same case and line. */
    void carry(UUID replaced, Collection<UUID> replacements);
}
