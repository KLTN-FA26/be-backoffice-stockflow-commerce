package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Summary;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws an appendix to a sales contract ("phụ lục hợp đồng") as a PDF: the paper the two parties sign when they change
 * a contract already signed - a quantity, a price, a delivery date - instead of signing the whole contract again.
 *
 * <p>Bộ luật Dân sự 2015 Điều 403 gives an appendix the force of the contract and lets the parties change the
 * contract by one, so it is set as the contract is: Quốc hiệu and Tiêu ngữ, the legal bases - the contract it belongs
 * to first among them - the two parties as the contract names them, numbered clauses, the two signature blocks. Each
 * change is a row with what the contract says beside what it now says: an appendix that states only the new wording
 * leaves the reader to find what it replaced. Where the change moves the goods or the money, the goods table and the
 * contract's value as they now stand follow, the total in words, so the appendix alone says what is owed.</p>
 *
 * <p>The closing clause says what practice expects of every appendix: that it is an inseparable part of the contract,
 * and that what it does not change stands. The words are the Vietnamese bundle's, the layout the kit's
 * ({@link BusinessDocument}) in black; what it shows is given, nothing is looked up here.</p>
 */
@Component
class ContractAppendixDocument {

    /**
     * Everything the appendix says.
     *
     * @param appendixNo which appendix of its contract this is: 1 for "PHỤ LỤC HỢP ĐỒNG SỐ 01"
     * @param number     the appendix's own number ("PL01-HD-20261109-000001-1")
     * @param seller     the seller as the contract names it - its copy, not today's configuration
     * @param reason     why the contract is changed; null to leave the line out
     * @param goods      the goods as they stand after the change; empty when the change moves neither goods nor money
     * @param totals     the contract's value after the change; null with empty {@code goods}
     * @param draft      a copy for review, marked across every page: an unsigned appendix changes nothing yet
     */
    record Facts(int appendixNo, String number, String contractNumber, LocalDate contractDate,
                 SalesContracts.Party seller, SalesContracts.Party buyer, LocalDate dated, String reason,
                 List<Change> changes, List<GoodsLine> goods, SalesContracts.Totals totals, boolean draft) {
    }

    /** One change: what is changed, what the contract says, what it says from now on. */
    record Change(String subject, String before, String after) {
    }

    /** A line of the goods after the change. */
    record GoodsLine(String code, String name, String unit, long quantity, BigDecimal unitPrice, BigDecimal amount,
                     BigDecimal taxRate) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    ContractAppendixDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        String contractDate = DocumentFormats.date(f.contractDate());
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.nationalHead(t));
        String title = t.getString("appendix.pdf.title").formatted(f.appendixNo());
        blocks.add(new Title(title, t.getString("document.number") + " " + f.number(), null));
        blocks.add(Paragraph.italic(t.getString("appendix.pdf.attached").formatted(f.contractNumber(), contractDate))
                .align(Align.CENTRE).after(8));
        blocks.add(DocumentParts.basis(t.getString("contract.pdf.basis.civil")));
        blocks.add(DocumentParts.basis(t.getString("appendix.pdf.basis.contract").formatted(f.contractNumber(),
                contractDate)));
        blocks.add(DocumentParts.basis(t.getString("appendix.pdf.basis.need")));
        blocks.add(DocumentParts.today(t, head, f.dated()));
        SalesDocumentLines.party(blocks, t, "contract.pdf.seller", f.seller(), head);
        SalesDocumentLines.party(blocks, t, "contract.pdf.buyer", f.buyer(), null);
        blocks.add(DocumentParts.lead(t.getString("appendix.pdf.agreed")));

        DocumentClauses clauses = new DocumentClauses(blocks, t, "appendix.pdf");
        clauses.title("changes");
        if (DocumentParts.filled(f.reason())) {
            clauses.point("changes.reason", f.reason());
        }
        clauses.point("changes.table", f.contractNumber());
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        for (Change change : f.changes()) {
            rows.add(List.of(Cell.of(String.valueOf(++no)), Cell.bold(change.subject()), Cell.of(change.before()),
                    Cell.of(change.after())));
        }
        blocks.add(new Table(List.of(new Column(t.getString("document.goods.no"), 30, Align.CENTRE),
                new Column(t.getString("appendix.pdf.col.subject"), 120, Align.LEFT),
                new Column(t.getString("appendix.pdf.col.before"), 158, Align.LEFT),
                new Column(t.getString("appendix.pdf.col.after"), 158, Align.LEFT)), rows));

        if (!f.goods().isEmpty()) {
            clauses.title("goods");
            clauses.point("goods.table");
            blocks.add(goods(t, f));
            SalesDocumentLines.inWords(blocks, t, "contract.pdf.inWords", f.totals().totalAmount());
        }

        clauses.title("general");
        clauses.point("general.part", f.contractNumber());
        clauses.point("general.others");
        clauses.point("general.from");
        clauses.point("general.copies");

        blocks.add(new Signatures(null, List.of(
                DocumentParts.signatory(t, t.getString("contract.pdf.sign.seller"),
                        SalesDocumentLines.party(t, f.seller(), null)),
                DocumentParts.signatory(t, t.getString("contract.pdf.sign.buyer"),
                        SalesDocumentLines.party(t, f.buyer(), null)))));
        return BusinessDocument.draw(fonts, DocumentParts.official(t, f.number(),
                f.draft() ? t.getString("contract.pdf.status.DRAFT") : null), title + " " + f.number(), blocks);
    }

    private static Table goods(ResourceBundle t, Facts f) {
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        for (GoodsLine line : f.goods()) {
            rows.add(SalesDocumentLines.goodsRow(++no, line.code(), line.name(), List.of(), line.unit(),
                    DocumentFormats.number(line.quantity()), line.unitPrice(), line.amount(), line.taxRate()));
        }
        return new Table(SalesDocumentLines.goodsColumns(t), rows, List.of(
                new Summary(t.getString("contract.pdf.net"), DocumentFormats.money(f.totals().netAmount()), false),
                new Summary(t.getString("document.total.tax"), DocumentFormats.money(f.totals().taxAmount()), false),
                new Summary(t.getString("contract.pdf.total"), DocumentFormats.money(f.totals().totalAmount()),
                        true)), SalesDocumentLines.AMOUNT_COLUMN);
    }
}
