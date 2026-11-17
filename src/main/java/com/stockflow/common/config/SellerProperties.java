package com.stockflow.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The company that sells: the seller a sales contract names (4B-4, kltn-docs 14 §4.3 step 11) and an e-invoice is issued
 * by (WF-05 E1/E2, kltn-docs 21), bound from {@code stockflow.seller.*} ({@code SELLER_*}). In {@code common} because
 * two modules name the same company - order on its contracts, invoicing on its invoices - and a second copy of the
 * values would let the two documents of one sale disagree.
 *
 * <p>Blank by default. local, dev and test carry a demo company; in the {@code prod} profile the application refuses to
 * start until every field is set ({@link SellerProfileRequirement}), so no contract or invoice ever names a made-up
 * company. Where it is blank anyway, a contract or an invoice is refused (503 {@code SELLER_PROFILE_UNAVAILABLE}).</p>
 */
@ConfigurationProperties(prefix = "stockflow.seller")
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
