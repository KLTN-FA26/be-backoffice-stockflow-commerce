package com.stockflow.order.internal.repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The labels a production order shows beside the ids it holds of its own module's rows - the sales order's number and
 * the producer's name - read for a whole page at once, so a list names them without a lookup per row.
 */
public interface ProductionOrderReferences {

    /** The order number of each of {@code orderIds}, in one statement; an unknown id is absent. */
    Map<UUID, String> orderNumbers(Collection<UUID> orderIds);

    /** The name of each of {@code producerIds}, in one statement; an unknown id is absent. */
    Map<UUID, String> producerNames(Collection<UUID> producerIds);
}
