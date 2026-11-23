package com.stockflow.invoicing.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws the agreement to adjust or replace an invoice ("biên bản thỏa thuận điều chỉnh / thay thế hóa đơn") as a PDF,
 * ready for the two companies to sign.
 *
 * <p>Thông tư 91/2026/TT-BTC Điều 10 makes a seller and a buyer that is an organisation or a business put in writing
 * what was wrong on an issued invoice before the seller adjusts or replaces it, and has the seller keep that paper for
 * the tax authority. It prescribes the content - "ghi rõ nội dung sai" - and no form. Until now the system only stored
 * a signed scan someone had typed up elsewhere; drawing it from the invoice's own data means the template, symbol,
 * number and date it names are the invoice's, not a retyping of them.</p>
 *
 * <p>Set as a paper two companies sign: Quốc hiệu and Tiêu ngữ, the bases - the decree and the circular in force since
 * 1 July 2026, never the ones they replaced - the two parties, the invoice concerned, the reason, and a table with
 * what the invoice says beside what is right for each item, so a reader sees the error without comparing two invoices.
 * The parties' undertaking and the count of copies close it, in the wording such agreements carry. The words are the
 * Vietnamese bundle's, the layout the kit's ({@link BusinessDocument}) in black; what it shows is given.</p>
 */
@Component
class InvoiceAdjustmentAgreementDocument {

    /**
     * Everything the agreement says.
     *
     * @param replacement      whether the invoice is replaced rather than adjusted: the seller chooses one, and an
     *                         invoice once handled one way is handled that way again (Điều 10 khoản 6)
     * @param contractNumber   the contract the sale was made under; null with {@code contractDate} when there is none
     * @param taxAuthorityCode the tax authority's code on the invoice; null for an invoice without one
     * @param reason           why the invoice is changed
     */
    record Facts(String number, LocalDate dated, boolean replacement, String contractNumber, LocalDate contractDate,
                 DocumentParts.Party buyer, String templateCode, String symbol, int invoiceNumber,
                 LocalDate invoiceDate, String taxAuthorityCode, String reason, List<Change> changes) {
    }

    /** One item of the invoice: what it says, and what is right. */
    record Change(String item, String recorded, String correct) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    InvoiceAdjustmentAgreementDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        DocumentParts.Party seller = DocumentParts.Party.of(t, head, letterhead.representative(),
                letterhead.representativeTitle());
        String kind = f.replacement() ? "replacement" : "adjustment";
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.nationalHead(t));
        String title = t.getString("invoice-agreement.pdf.title." + kind);
        blocks.add(new Title(title, t.getString("document.number") + " " + f.number(), null));
        if (DocumentParts.filled(f.contractNumber()) && f.contractDate() != null) {
            blocks.add(DocumentParts.basis(t.getString("invoice-agreement.pdf.basis.contract")
                    .formatted(f.contractNumber(), DocumentFormats.date(f.contractDate()))));
        }
        blocks.add(DocumentParts.basis(t.getString("invoice-agreement.pdf.basis.decree")));
        blocks.add(DocumentParts.basis(t.getString("invoice-agreement.pdf.basis.circular")));
        blocks.add(DocumentParts.today(t, head, f.dated()));
        DocumentParts.party(blocks, t, t.getString("document.party.seller"), seller);
        DocumentParts.party(blocks, t, t.getString("document.party.buyer"), f.buyer());

        String invoice = t.getString("invoice-agreement.pdf.invoice").formatted(f.templateCode(), f.symbol(),
                "%08d".formatted(f.invoiceNumber()), DocumentFormats.longDate(f.invoiceDate()));
        if (DocumentParts.filled(f.taxAuthorityCode())) {
            invoice = invoice + t.getString("invoice-agreement.pdf.invoice.code").formatted(f.taxAuthorityCode());
        }
        blocks.add(DocumentParts.lead(t.getString("invoice-agreement.pdf.agreed").formatted(invoice,
                t.getString("invoice-agreement.pdf.will." + kind))));
        blocks.add(Paragraph.of(Run.bold(t.getString("invoice-agreement.pdf.reason." + kind) + " "),
                Run.of(f.reason())).firstIndent(28));
        blocks.add(DocumentParts.lead(t.getString("invoice-agreement.pdf.changes." + kind)).after(0));
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        for (Change change : f.changes()) {
            rows.add(List.of(Cell.of(String.valueOf(++no)), Cell.bold(change.item()), Cell.of(change.recorded()),
                    Cell.of(change.correct())));
        }
        blocks.add(new Table(List.of(new Column(t.getString("document.goods.no"), 30, Align.CENTRE),
                new Column(t.getString("invoice-agreement.pdf.col.item"), 130, Align.LEFT),
                new Column(t.getString("invoice-agreement.pdf.col.recorded"), 153, Align.LEFT),
                new Column(t.getString("invoice-agreement.pdf.col.correct"), 153, Align.LEFT)), rows));

        blocks.add(DocumentParts.lead(t.getString("invoice-agreement.pdf.commit." + kind)).after(2));
        blocks.add(DocumentParts.lead(t.getString("invoice-agreement.pdf.copies")));
        blocks.add(new Signatures(null, List.of(
                DocumentParts.signatory(t, t.getString("document.sign.partyA"), seller),
                DocumentParts.signatory(t, t.getString("document.sign.partyB"), f.buyer()))));
        return BusinessDocument.draw(fonts, DocumentParts.official(t, f.number(), null), title + " " + f.number(),
                blocks);
    }
}
