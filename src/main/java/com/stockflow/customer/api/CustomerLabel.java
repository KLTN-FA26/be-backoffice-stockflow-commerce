package com.stockflow.customer.api;

import java.util.UUID;

/**
 * What a document shows to say whose it is - the customer's registered name and the code it is known by - and nothing
 * else of the profile (kltn-docs 18 BR-06: the profile itself stays behind the customer permissions). Read by modules
 * that list another module's rows per customer (payment installments and receipts, orders) so staff who may read those
 * rows see a name, not an id.
 *
 * @param name the registered full name (the company name of a B2B customer)
 * @param code the same code {@link CustomerSummary#code()} gives: the tax code, or {@code KH} and eight characters of
 *             the id for a customer created before tax codes were kept
 */
public record CustomerLabel(UUID customerId, String name, String code) {
}
