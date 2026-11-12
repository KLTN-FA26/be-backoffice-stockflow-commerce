package com.stockflow.common.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In production the seller's company details are required (WF-05 E1): the application refuses to start without them.
 *
 * <p>Everywhere else a blank seller only refuses the documents that name it, one request at a time (503
 * {@code SELLER_PROFILE_UNAVAILABLE}). That is too late in production: an e-invoice is drawn up by a listener when a
 * delivery is recorded, with nobody there to read a 503, and an invoice or a contract of a real sale must never be
 * drawn up for a company nobody entered. Failing at startup puts the mistake in front of whoever deploys.</p>
 */
@Component
@Profile("prod")
class SellerProfileRequirement {

    SellerProfileRequirement(SellerProperties seller) {
        if (!seller.complete()) {
            throw new IllegalStateException("stockflow.seller.* is incomplete: set SELLER_NAME, SELLER_TAX_CODE, "
                    + "SELLER_ADDRESS, SELLER_REPRESENTATIVE and SELLER_REPRESENTATIVE_TITLE before starting in the "
                    + "prod profile");
        }
    }
}
