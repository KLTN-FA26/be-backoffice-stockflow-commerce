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

    /**
     * WF-05 BE-13: cartons of a later return receipt of the case's shipment, added to the case - whatever its status,
     * the cartons being a record of what came back (append-only). A carton the case lists already (an event seen again)
     * is left as it is.
     *
     * @return how many were added
     */
    int appendCartons(UUID caseId, java.util.List<DeliveryFailureCase.Carton> cartons);

    /** The order's cases, oldest first. */
    List<DeliveryFailureCase> ofOrder(UUID orderId);

    /**
     * Cases in {@code status} (any when null), newest first (WF-05: most are records the system closed).
     * {@code warehouses} not null keeps
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
     * RTM-RETURNED: orders a shipment of which came back, waiting for the refund of what it closed - RETURNED with
     * nothing delivered, or PARTIALLY_FULFILLED with every other shipment delivered - oldest return first.
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
