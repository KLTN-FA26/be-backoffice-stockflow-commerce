package com.stockflow.common.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In production the seller's company details are required and must be usable (WF-05 E1): the application refuses to
 * start otherwise.
 *
 * <p>Everywhere else a blank seller only refuses the documents that name it, one request at a time (503
 * {@code SELLER_PROFILE_UNAVAILABLE}). That is too late in production: an e-invoice is drawn up by a listener when a
 * delivery is recorded, with nobody there to read a 503, and an invoice or a contract of a real sale must never be
 * drawn up for a company nobody entered. Failing at startup puts the mistake in front of whoever deploys.</p>
 *
 * <p>Present is not enough. The values are copied onto every contract and invoice, into columns of a fixed length
 * ({@code ordering.sales_contract}, {@code invoicing.einvoice}: name 200, tax code 14, address 500, representative
 * 200, title 100), and the tax code is what the tax authority knows the seller by. A tax code typed with a note after
 * it, or a name too long for its column, would start fine and then fail every draft at the insert - no delivery
 * invoiced, every event retried for ever. So the shape is checked here too: the tax code as an organisation's is
 * written (10 digits, or 10-3 for a dependent unit - a seller is not a household business), and each length against
 * its column.</p>
 */
@Component
@Profile("prod")
class SellerProfileRequirement {

    private static final Pattern TAX_CODE = Pattern.compile("\\d{10}(-\\d{3})?");
    static final int NAME_MAX = 200;
    static final int ADDRESS_MAX = 500;
    static final int REPRESENTATIVE_MAX = 200;
    static final int TITLE_MAX = 100;

    SellerProfileRequirement(SellerProperties seller) {
        if (!seller.complete()) {
            throw new IllegalStateException("stockflow.seller.* is incomplete: set SELLER_NAME, SELLER_TAX_CODE, "
                    + "SELLER_ADDRESS, SELLER_REPRESENTATIVE and SELLER_REPRESENTATIVE_TITLE before starting in the "
                    + "prod profile");
        }
        List<String> wrong = new ArrayList<>();
        if (!TAX_CODE.matcher(seller.taxCode().strip()).matches()) {
            wrong.add("SELLER_TAX_CODE must be 10 digits, or 10-3 for a dependent unit, and nothing else");
        }
        tooLong(wrong, "SELLER_NAME", seller.name(), NAME_MAX);
        tooLong(wrong, "SELLER_ADDRESS", seller.address(), ADDRESS_MAX);
        tooLong(wrong, "SELLER_REPRESENTATIVE", seller.representative(), REPRESENTATIVE_MAX);
        tooLong(wrong, "SELLER_REPRESENTATIVE_TITLE", seller.representativeTitle(), TITLE_MAX);
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("stockflow.seller.* cannot be put on a contract or an invoice: "
                    + String.join("; ", wrong));
        }
    }

    private static void tooLong(List<String> wrong, String variable, String value, int max) {
        if (value.strip().length() > max) {
            wrong.add(variable + " is longer than " + max + " characters");
        }
    }
}
