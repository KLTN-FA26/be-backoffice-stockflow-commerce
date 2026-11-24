package com.stockflow.payment.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which goods of an order have left the warehouse with a carrier (WF-05 decision Q-20, BE-33): what payment asks before
 * it voids a receipt that paid an installment due on signing or before delivery - such a receipt is what let those
 * goods go, so it is not voided once they went.
 *
 * <p>Every shipment the order knows of counts, whatever happened to it afterwards (delivered, failed, brought back,
 * lost): the order learns of a shipment only when it is handed over (kltn-docs 09 §5).</p>
 *
 * @param any      some goods of the order were handed over to a carrier: an installment due on signing, or due before
 *                 delivery and tied to no batch, has let goods leave
 * @param batchIds the delivery batches with goods handed over; an installment before delivery tied to one of them has
 *                 let that batch leave
 */
public record HandedOverGoods(boolean any, Set<UUID> batchIds) {

    /** Nothing of the order has left. */
    public static final HandedOverGoods NONE = new HandedOverGoods(false, Set.of());

    public HandedOverGoods {
        batchIds = batchIds == null ? Set.of() : Set.copyOf(batchIds);
    }
}
