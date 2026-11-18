package com.stockflow.invoicing.internal.domain;

/**
 * A party as an invoice names it: copied onto the invoice when it is drawn up, so a later change of the customer's
 * profile or of the seller's configuration leaves an invoice already issued as it was (kltn-docs 21 BR-06).
 *
 * <p>The name and the address are kept whole, as registered. Where a provider's field is shorter they are written with
 * the abbreviations the law allows only on the rendered invoice ({@link InvoiceAbbreviations}), never here.</p>
 *
 * @param email where the invoice is sent; null on the seller, and on a buyer whose profile has none yet
 */
public record InvoiceParty(String name, String taxCode, String address, String email) {

    public InvoiceParty {
        name = strip(name);
        taxCode = strip(taxCode);
        address = strip(address);
        email = strip(email);
    }

    /**
     * Whether a buyer can be invoiced (NĐ 254 Phụ lục điểm 4.b: an invoice without the buyer's details is worth nothing
     * to the buyer; kltn-docs 21 BR-06): its name, tax code and address. The same rule is
     * {@code ck_einvoice_buyer_complete}.
     *
     * <p>Not the e-mail: it is not invoice content in the law's list (legal review WF05-E0 L7), so the database does not
     * ask for it. The business does (kltn-docs 21 BR-06, HĐ-8): a draft whose buyer has none carries
     * {@code BUYER_EMAIL_MISSING} and is refused issuing under a code of its own, so the two are told apart.</p>
     */
    public boolean completeBuyer() {
        return name != null && taxCode != null && address != null;
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
