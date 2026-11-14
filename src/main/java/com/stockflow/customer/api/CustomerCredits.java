package com.stockflow.customer.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The credit of many customers at once (kltn-docs 18 §3, 15 BR-12), for the receivables report (15 §4.3; 2026-11-14
 * plan, BE2-B): each customer's limit and term beside what they owe, read in one query. {@link
 * CustomerService#creditTerms} answers one customer, and refuses an unknown one; a report asks for the customers that
 * owe something and takes what exists.
 */
public interface CustomerCredits {

    /**
     * {@link CustomerService#creditTerms} of each of these customers that exists: their terms as accounting approved
     * them, or {@link CreditTerms#none} for a customer nobody set credit for. Unknown ids are absent; null or empty in,
     * empty out.
     */
    Map<UUID, CreditTerms> creditTermsOf(Collection<UUID> customerIds);
}
