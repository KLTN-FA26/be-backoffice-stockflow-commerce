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
     * to the buyer; kltn-docs 21 BR-06): its name, tax code and address, and the e-mail address the invoice goes to.
     * The same rule is {@code ck_einvoice_buyer_complete}.
     */
    public boolean completeBuyer() {
        return name != null && taxCode != null && address != null && email != null;
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
