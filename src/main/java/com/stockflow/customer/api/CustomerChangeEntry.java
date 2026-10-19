package com.stockflow.customer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One change of a customer's company name ({@code FULL_NAME}) or tax code ({@code TAX_CODE}), staff only: the values
 * are whole, as on the staff detail.
 */
public record CustomerChangeEntry(String field, String oldValue, String newValue, UUID changedBy, Instant changedAt) {
}
