package com.stockflow.product.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How many customers' designs wait for review on each print configuration. Implemented by {@code design}; see the
 * package documentation for why the interface lives here.
 */
public interface PendingDesignReviews {

    /**
     * The designs waiting for review (PENDING_REVIEW) drawn on each of {@code templateIds}, in one query. A template
     * with none is absent; empty in, empty out.
     */
    Map<UUID, Long> countByTemplate(Collection<UUID> templateIds);
}
