package com.stockflow.customer.api;

import java.util.List;
import java.util.UUID;

/**
 * Which customers a search box means, for a module that filters its own rows by customer and may not JOIN this schema
 * (ADR-0007): the accountant's installment list searches by customer name or tax code without holding the customer
 * permissions (FE BE-16, kltn-docs 18 BR-06). Its own interface, as {@link CustomerSummaries} is: a list filter needs
 * this and nothing else of the customer.
 */
public interface CustomerSearch {

    /**
     * The customers whose name or tax code contains {@code search}, case and Vietnamese accents aside, at most
     * {@code limit} of them in id order; blank in, empty out.
     *
     * <p>A part of a tax code matches here, while the customer list's own search matches a tax code only whole so
     * that it cannot be used to walk the codes. That is safe because this answers ids only and the caller shows only its
     * own rows of them, which already carry the customer's code: nothing is learnt that those rows did not show. A
     * caller asks for one more than it accepts, to tell a search too broad from one cut short.</p>
     */
    List<UUID> idsMatching(String search, int limit);
}
