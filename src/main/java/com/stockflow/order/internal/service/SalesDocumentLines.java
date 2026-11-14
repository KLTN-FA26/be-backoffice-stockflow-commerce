package com.stockflow.order.internal.service;

import com.stockflow.common.label.PrintedDocument;
import com.stockflow.common.label.PrintedDocument.Line;
import com.stockflow.common.text.VietnameseAmountInWords;

import java.math.BigDecimal;
import java.util.List;
import java.util.ResourceBundle;

/**
 * The lines every sales document of the module draws the same way - the sales contract (4B-4) and the quote (kltn-docs
 * 14 §4.1): a party as the seller or the buyer, an amount, and a total in words. One place, so a quote and the contract
 * it becomes name the two companies and spell a total identically; the labels are the contract's ({@code contract.pdf.*}),
 * the vocabulary both documents share.
 */
final class SalesDocumentLines {

    private SalesDocumentLines() {
    }

    /**
     * A party under its heading: the name, then each of tax code, address and representative that is known. A contract
     * names both parties in full (14 BR-05 refuses one without its legal profile); a quote may be drawn up for a
     * customer whose registered address and representative are not recorded yet, and leaves those lines out rather
     * than printing blanks.
     */
    static void party(List<Line> lines, ResourceBundle t, String headingKey, SalesContracts.Party party) {
        lines.add(Line.heading(t.getString(headingKey)));
        lines.add(Line.bold(party.name()));
        if (filled(party.taxCode())) {
            lines.add(Line.text(t.getString("contract.pdf.taxCode") + ": " + party.taxCode()));
        }
        if (filled(party.address())) {
            lines.add(Line.text(t.getString("contract.pdf.address") + ": " + party.address()));
        }
        if (filled(party.representative())) {
            lines.add(Line.text(t.getString("contract.pdf.representative") + ": " + party.representative()
                    + (filled(party.representativeTitle()) ? " - " + party.representativeTitle() : "")));
        }
    }

    /**
     * The total in words under the total ("Bằng chữ", 14 §4.3): the words settle a misread figure. Left out for an
     * amount that is not whole dong or not known yet - a document is drawn, not refused, for want of it.
     */
    static void inWords(List<Line> lines, ResourceBundle t, BigDecimal total) {
        VietnameseAmountInWords.ofWhole(total)
                .ifPresent(words -> lines.add(Line.text(t.getString("contract.pdf.inWords") + ": " + words)));
    }

    /** Whole dong, grouped with dots as Vietnamese documents write it: 1.234.567 VND. */
    static String money(BigDecimal amount) {
        return PrintedDocument.money(amount, "VND");
    }

    /** A VAT rate as a percentage without trailing zeros: 10, 8, 0. */
    static String rate(BigDecimal rate) {
        return rate == null ? "0" : rate.stripTrailingZeros().toPlainString();
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
