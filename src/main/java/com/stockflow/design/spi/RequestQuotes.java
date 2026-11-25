package com.stockflow.design.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The quote Sales drew up from a customer's quote request, if any (R5-5: a closed request links to the quote that
 * answered it). Implemented by {@code order}: a quote drawn from a request carries a request id derived from it, so the
 * same request always names the same quote, whichever screen drew it.
 */
public interface RequestQuotes {

    /**
     * @param sent whether the quote was ever sent to the customer: before that it is Sales' working copy, which the
     *             customer cannot open
     * @param withdrawn the quote was cancelled before it was ever sent: Sales closed the request without answering it
     *                  (LC-3)
     */
    record Answer(UUID quoteId, String quoteNumber, boolean sent, boolean withdrawn) {
    }

    /** The quote drawn from each of {@code requestIds} (requests on one design), by request id; none is absent. */
    Map<UUID, Answer> ofDesignRequests(Collection<UUID> requestIds);

    /** The same for project quote requests. */
    Map<UUID, Answer> ofProjectRequests(Collection<UUID> requestIds);
}
