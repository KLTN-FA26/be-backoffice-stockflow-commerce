package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.BrandHeader;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.ResourceBundle;

/**
 * The parts every sales document of the module draws the same way - the quotation (kltn-docs 14 §4.1) and the sales
 * contract it becomes (4B-4): our letterhead, a party's block, the goods table's columns and a goods row, an amount, a
 * total in words. One place, so a quote and its contract name the two companies, head the same columns and spell a
 * total identically; the shared words are the {@code document.*} keys of the Vietnamese bundle.
 */
final class SalesDocumentLines {

    /**
     * The brand colour of the company's papers: the dark green of its logo. A constant, not configuration - a second
     * company would bring its own palette with its own logo, which is a change of more than one value.
     */
    static final Color ACCENT = DocumentParts.ACCENT;

    /** The amount column of {@link #goodsColumns}: where a goods table's totals are written. */
    static final int AMOUNT_COLUMN = 6;

    private SalesDocumentLines() {
    }

    /** Our company's head on a commercial paper ({@link DocumentParts#brandHeader}). */
    static BrandHeader brandHeader(ResourceBundle t, Letterhead head) {
        return DocumentParts.brandHeader(t, head);
    }

    /** "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026" ({@link DocumentParts#placeDate}). */
    static String placeDate(ResourceBundle t, Letterhead head, LocalDate dated) {
        return DocumentParts.placeDate(t, head, dated);
    }

    /**
     * A party's block: its role and name in bold, then each of address, tax code and representative that is known,
     * their colons in one column. A contract names both parties in full (14 BR-05 refuses one without its legal
     * profile); a quote may be drawn up for a customer whose registered address and representative are not recorded
     * yet, and leaves those lines out rather than printing blanks.
     *
     * @param contact our letterhead, for the seller: its phone, e-mail and the account it is paid into go into the
     *                block, as a contract's party block carries them; null for the buyer, whose we do not keep
     */
    static void party(List<Block> blocks, ResourceBundle t, String roleKey, SalesContracts.Party party,
                      Letterhead contact) {
        DocumentParts.party(blocks, t, t.getString(roleKey), party(t, party, contact));
    }

    /** {@code party} as every module's papers name a company ({@link DocumentParts.Party}). */
    static DocumentParts.Party party(ResourceBundle t, SalesContracts.Party party, Letterhead contact) {
        if (contact == null) {
            return new DocumentParts.Party(party.name(), party.taxCode(), party.address(), party.representative(),
                    party.representativeTitle());
        }
        DocumentParts.Party ours = DocumentParts.Party.of(t, contact, party.representative(),
                party.representativeTitle());
        // The legal identity is the contract's own copy, not today's letterhead: only the contacts are today's.
        return new DocumentParts.Party(party.name(), party.taxCode(), party.address(), ours.phone(), ours.email(),
                ours.account(), party.representative(), party.representativeTitle());
    }

    /** "Ngân hàng ... - Chi nhánh ...", or the bank alone. */
    static String bank(Letterhead head) {
        return DocumentParts.bank(head);
    }

    /**
     * The goods table's columns, as a Vietnamese quotation and a contract's first clause head them: No, code, name and
     * specification, unit, quantity, unit price, amount, VAT rate. Figures right-aligned; the currency is in the
     * heading, not after every figure.
     */
    static List<Column> goodsColumns(ResourceBundle t) {
        return List.of(new Column(t.getString("document.goods.no"), 30, Align.CENTRE),
                new Column(t.getString("document.goods.code"), 84, Align.LEFT),
                new Column(t.getString("document.goods.name"), 138, Align.LEFT),
                new Column(t.getString("document.goods.unit"), 40, Align.CENTRE),
                new Column(t.getString("document.goods.quantity"), 50, Align.RIGHT),
                new Column(t.getString("document.goods.unitPrice"), 54, Align.RIGHT),
                new Column(t.getString("document.goods.amount"), 74, Align.RIGHT),
                new Column(t.getString("document.goods.vat"), 44, Align.CENTRE));
    }

    /**
     * A goods row.
     *
     * @param name    the product's name, bold; the code again where the product has none recorded
     * @param details the specification under the name: cartons, print, price tiers
     * @param price   null for a part of a draft not priced yet, drawn as a dash
     */
    static List<Cell> goodsRow(int no, String code, String name, List<String> details, String unit, String quantity,
                               BigDecimal price, BigDecimal amount, BigDecimal rate) {
        return List.of(Cell.of(String.valueOf(no)), Cell.of(code == null ? "" : code),
                Cell.bold(filled(name) ? name : code).details(details), Cell.of(unit), Cell.of(quantity),
                Cell.of(amount(price)), Cell.of(amount(amount)),
                Cell.of(rate == null ? "-" : DocumentFormats.rate(rate)));
    }

    /** The amount in dong, or a dash where it is not known yet (a draft's line without its price). */
    static String amount(BigDecimal amount) {
        return amount == null ? "-" : DocumentFormats.money(amount);
    }

    /**
     * The total in words under the total (14 §4.3): the words settle a misread figure. Left out for an amount that is
     * not whole dong or not known yet - a document is drawn, not refused, for want of it.
     */
    static void inWords(List<Block> blocks, ResourceBundle t, String labelKey, BigDecimal total) {
        DocumentParts.inWords(blocks, t.getString(labelKey), total);
    }

    /**
     * A representative's title as a signature block heads it: in capitals ("GIÁM ĐỐC"); null when not recorded, so
     * the block is headed by its party alone.
     */
    static String position(SalesContracts.Party party) {
        return DocumentParts.position(party.representativeTitle());
    }

    static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
