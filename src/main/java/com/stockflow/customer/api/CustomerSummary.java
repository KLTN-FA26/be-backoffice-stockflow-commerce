package com.stockflow.customer.api;

import java.time.Instant;
import java.util.UUID;

/** Cross-module customer profile view. */
public record CustomerSummary(
        UUID customerId,
        UUID userId,
        String fullName,
        String email,
        String phone,
        /* Normalised tax code; null for a customer created before SCRUM-450. */
        String taxCode,
        UUID segmentId,
        String status,
        long version,
        Instant createdAt
) {

    /**
     * The customer as a document code spells them - a customer's own SKU is "product - customer - number" (kltn-docs 12
     * §4.5, B20): the tax code a B2B customer is known by, or {@code KH} and eight characters of the id for a customer
     * created before tax codes were kept. Capitals, digits and hyphens only, at most 20 characters.
     */
    public String code() {
        if (taxCode != null) {
            String spelled = taxCode.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
            if (!spelled.isEmpty() && Character.isLetterOrDigit(spelled.charAt(0))) {
                return spelled.length() > 20 ? spelled.substring(0, 20) : spelled;
            }
        }
        String id = customerId.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return "KH" + id.substring(id.length() - 8);
    }
}
