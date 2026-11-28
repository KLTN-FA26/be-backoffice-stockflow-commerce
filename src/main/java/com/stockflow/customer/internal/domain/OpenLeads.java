package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for the leads a salesperson is working on ({@code customer.lead}, {@code customer.lead_history}).
 *
 * <p>Owner decision D44: when a salesperson's account is locked or disabled, their open leads go back to the managers'
 * queue. The lead aggregate and its endpoints come with the lead feature; this port is the one write the foundation
 * needs, kept apart so the lead service can take it over.</p>
 */
public interface OpenLeads {

    /**
     * Every lead of {@code salesUserId} still ASSIGNED or CONTACTED goes back to NEW with no Sales, each with a history
     * row carrying {@code note}; the rows are locked in id order. A lead CONVERTED or DISQUALIFIED is left alone.
     *
     * @return the ids of the leads released
     */
    List<UUID> releaseOpenLeadsOf(UUID salesUserId, String note, UUID byUserId, Instant at);
}
