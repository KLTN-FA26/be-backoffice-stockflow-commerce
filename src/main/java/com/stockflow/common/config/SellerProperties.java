package com.stockflow.order.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The seller a sales contract names (4B-4, kltn-docs 14 §4.3 step 11), bound from {@code stockflow.order.seller.*}.
 * Blank by default and in production until the deployment sets it: a contract is then refused (503
 * {@code SELLER_PROFILE_UNAVAILABLE}) rather than drawn up for a made-up company.
 */
@ConfigurationProperties(prefix = "stockflow.order.seller")
public record SellerProperties(String name, String taxCode, String address, String representative,
                               String representativeTitle) {

    /** Whether every field is set: a contract names them all. */
    public boolean complete() {
        return filled(name) && filled(taxCode) && filled(address) && filled(representative)
                && filled(representativeTitle);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
