package com.stockflow.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The company that sells: the seller a sales contract names (4B-4, kltn-docs 14 §4.3 step 11) and an e-invoice is issued
 * by (WF-05 E1/E2, kltn-docs 21), bound from {@code stockflow.seller.*} ({@code SELLER_*}). In {@code common} because
 * two modules name the same company - order on its contracts, invoicing on its invoices - and a second copy of the
 * values would let the two documents of one sale disagree.
 *
 * <p>Blank by default. local, dev and test carry a demo company; in the {@code prod} profile the application refuses to
 * start until the five legal fields are set ({@link SellerProfileRequirement}), so no quote, contract or invoice ever
 * names a made-up company. Where they are blank anyway, a quote's PDF, a contract or an invoice is refused (503
 * {@code SELLER_PROFILE_UNAVAILABLE}).</p>
 *
 * <p>The first five fields are the company's legal identity: what a contract and an invoice must name, and what
 * {@link #complete()} asks for. The others are its letterhead - how to reach it, where to pay it, its logo - printed
 * at the head of a quotation and in a contract's party block. A document is drawn without the ones that are blank
 * rather than refused: a company with no website still sends quotations.</p>
 *
 * @param phone             as the company prints it: "(028) 3765 0000"
 * @param bankAccountNumber the account customers pay into. One account per deployment: it defaults to the one the
 *                          transfer instructions show ({@code stockflow.payment.company-bank.*}), so a contract and
 *                          the payment screen cannot name two
 * @param bankName          the bank's name as a contract writes it
 * @param bankBranch        the branch the account is held at
 * @param city              where the company's papers are dated from: "Thành phố Hồ Chí Minh, ngày ... tháng ..."
 * @param logo              a Spring resource location of a PNG ({@code classpath:branding/seller-logo.png},
 *                          {@code file:/etc/stockflow/logo.png}); blank for a letterhead without one
 */
@ConfigurationProperties(prefix = "stockflow.seller")
public record SellerProperties(String name, String taxCode, String address, String representative,
                               String representativeTitle, String phone, String email, String website,
                               String bankAccountNumber, String bankName, String bankBranch, String city,
                               String logo) {

    @ConstructorBinding
    public SellerProperties {
    }

    /** The legal identity alone, with no letterhead: what a contract and an invoice need. */
    public SellerProperties(String name, String taxCode, String address, String representative,
                            String representativeTitle) {
        this(name, taxCode, address, representative, representativeTitle, null, null, null, null, null, null, null,
                null);
    }

    /** Whether every field of the legal identity is set: a contract names them all. */
    public boolean complete() {
        return filled(name) && filled(taxCode) && filled(address) && filled(representative)
                && filled(representativeTitle);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
