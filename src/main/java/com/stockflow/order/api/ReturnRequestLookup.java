package com.stockflow.order.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A return request (RMA) as the warehouse needs it to take the goods back (team plan 4D-2; kltn-docs 03 §4.2 step 9,
 * 17 §4.10, BR-18): whose order it is, and whether its goods are expected now. Read-only, in no data scope: the
 * warehouse receiving the goods is not the Sales person the return belongs to, and the receipt checks its own
 * warehouse before it shows anything.
 */
public interface ReturnRequestLookup {

    /** The return request; empty when there is none with this id. */
    Optional<ReturnRequestReference> find(UUID returnRequestId);
}
