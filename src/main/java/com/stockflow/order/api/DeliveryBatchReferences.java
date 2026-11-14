package com.stockflow.order.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A delivery batch as a printed document names it (kltn-docs 09 §4.2, the delivery note of a shipment): by the number
 * of the contract whose schedule holds it and its place in that schedule - the words the customer's contract uses -
 * never by an id. Read-only.
 */
public interface DeliveryBatchReferences {

    /**
     * @param contractNumber the order's live contract
     * @param seq            the batch's place in its delivery schedule, from 1
     */
    record Reference(String contractNumber, int seq) {
    }

    /** The batch {@code deliveryBatchId} of the live contract of order {@code orderId}; empty when it holds none such. */
    Optional<Reference> of(UUID orderId, UUID deliveryBatchId);
}
