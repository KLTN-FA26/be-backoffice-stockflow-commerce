package com.stockflow.payment.internal.service;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws the record that reconciles a customer's debt ("biên bản đối chiếu công nợ") as a PDF: what the two companies'
 * accountants sign to agree, as of a day, how much the customer owes - the paper a later demand for payment, and the
 * liquidation of the contract, rest on.
 *
 * <p>No law fixes its form; practice asks for both parties' signatures and seals, so it is set as a record two
 * companies sign: our company and the record's number beside Quốc hiệu and Tiêu ngữ, the bases, the two parties, the
 * figures, a conclusion, two signature blocks. The figures are a ledger, not three totals: a contract here is invoiced
 * by delivery and paid by instalment, so every invoice, payment, refund and adjusting invoice of the period is a row
 * with its date and number, what it added to the debt or took off it, and the balance after it. A customer's
 * accountant checks such a paper row by row against their own ledger, and a single disagreeing row is found at once
 * where a disagreeing total is not.</p>
 *
 * <p>The running balance and the closing balance are worked out here from the opening balance and the rows, never
 * given: the conclusion the parties sign must be the sum of the rows above it. A closing balance below zero is said as
 * what it is - the seller owes the customer. The words are the Vietnamese bundle's, the layout the kit's
 * ({@link BusinessDocument}) in black; what it shows is given, nothing is looked up here.</p>
 */
@Component
class DebtReconciliationDocument {

    /**
     * Everything the record says.
     *
     * @param contractNumber the contract the debt arises under; null when the period covers several
     * @param opening        what the customer owed at the start of {@code from}
     * @param overdue        how much of the closing balance is past its due date; null or zero to leave the line out
     */
    record Facts(String number, LocalDate dated, LocalDate from, LocalDate to, String contractNumber,
                 DocumentParts.Party buyer, BigDecimal opening, List<Entry> entries, BigDecimal overdue) {
    }

    /**
     * One document of the period.
     *
     * @param debit  what it added to the debt (an invoice); zero for none
     * @param credit what it took off the debt (a payment, an adjustment down); zero for none
     */
    record Entry(LocalDate date, String reference, String description, BigDecimal debit, BigDecimal credit) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    DebtReconciliationDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        DocumentParts.Party seller = DocumentParts.Party.of(t, head, letterhead.representative(),
                letterhead.representativeTitle());
        String from = DocumentFormats.date(f.from());
        String to = DocumentFormats.date(f.to());
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.recordHead(t, head, f.number(), f.dated()));
        String title = t.getString("debt-reconciliation.pdf.title");
        blocks.add(new Title(title, null, t.getString("debt-reconciliation.pdf.period").formatted(from, to)));
        if (DocumentParts.filled(f.contractNumber())) {
            blocks.add(DocumentParts.basis(t.getString("debt-reconciliation.pdf.basis.contract")
                    .formatted(f.contractNumber())));
        }
        blocks.add(DocumentParts.basis(t.getString("debt-reconciliation.pdf.basis.records")));
        blocks.add(DocumentParts.basis(t.getString("debt-reconciliation.pdf.basis.agreement")));
        blocks.add(DocumentParts.today(t, head, f.dated()));
        DocumentParts.party(blocks, t, t.getString("document.party.seller"), seller);
        DocumentParts.party(blocks, t, t.getString("document.party.buyer"), f.buyer());
        blocks.add(DocumentParts.lead(t.getString("debt-reconciliation.pdf.agreed").formatted(from, to)));

        BigDecimal balance = f.opening();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(total(t.getString("debt-reconciliation.pdf.opening"), "", "", balance));
        int no = 0;
        for (Entry entry : f.entries()) {
            balance = balance.add(entry.debit()).subtract(entry.credit());
            debits = debits.add(entry.debit());
            credits = credits.add(entry.credit());
            rows.add(List.of(Cell.of(String.valueOf(++no)), Cell.of(DocumentFormats.date(entry.date())),
                    Cell.of(entry.reference()), Cell.of(entry.description()), Cell.of(amount(entry.debit())),
                    Cell.of(amount(entry.credit())), Cell.of(DocumentFormats.money(balance))));
        }
        rows.add(total(t.getString("debt-reconciliation.pdf.movements"), DocumentFormats.money(debits),
                DocumentFormats.money(credits), null));
        rows.add(total(t.getString("debt-reconciliation.pdf.closing"), "", "", balance));
        blocks.add(new Table(List.of(new Column(t.getString("document.goods.no"), 26, Align.CENTRE),
                new Column(t.getString("debt-reconciliation.pdf.col.date"), 56, Align.CENTRE),
                new Column(t.getString("debt-reconciliation.pdf.col.reference"), 104, Align.LEFT),
                new Column(t.getString("debt-reconciliation.pdf.col.description"), 102, Align.LEFT),
                new Column(t.getString("debt-reconciliation.pdf.col.debit"), 62, Align.RIGHT),
                new Column(t.getString("debt-reconciliation.pdf.col.credit"), 62, Align.RIGHT),
                new Column(t.getString("debt-reconciliation.pdf.col.balance"), 66, Align.RIGHT)), rows));

        String key = balance.signum() > 0 ? "debt-reconciliation.pdf.conclusion.owes"
                : balance.signum() < 0 ? "debt-reconciliation.pdf.conclusion.owed"
                : "debt-reconciliation.pdf.conclusion.none";
        blocks.add(Paragraph.of(Run.bold(t.getString("debt-reconciliation.pdf.conclusion") + " "),
                Run.of(t.getString(key).formatted(to, DocumentFormats.money(balance.abs())))).firstIndent(28));
        if (balance.signum() != 0) {
            DocumentParts.inWords(blocks, t.getString("document.inWords"), balance.abs());
        }
        if (f.overdue() != null && f.overdue().signum() > 0) {
            blocks.add(DocumentParts.lead(t.getString("debt-reconciliation.pdf.overdue")
                    .formatted(DocumentFormats.money(f.overdue()))));
        }
        blocks.add(DocumentParts.lead(t.getString("debt-reconciliation.pdf.copies")));

        blocks.add(new Signatures(null, List.of(
                DocumentParts.signatory(t, t.getString("document.sign.partyA"), seller),
                DocumentParts.signatory(t, t.getString("document.sign.partyB"), f.buyer()))));
        return BusinessDocument.draw(fonts, DocumentParts.official(t, f.number(), null), title + " " + f.number(),
                blocks);
    }

    /** A row that states a balance or sums the period, in bold across the columns an entry fills. */
    private static List<Cell> total(String label, String debit, String credit, BigDecimal balance) {
        return List.of(Cell.of(""), Cell.of(""), Cell.of(""), Cell.bold(label), Cell.bold(debit), Cell.bold(credit),
                Cell.bold(balance == null ? "" : DocumentFormats.money(balance)));
    }

    /** An amount, or nothing where the document moved nothing on that side: a column of zeros hides the figures. */
    private static String amount(BigDecimal amount) {
        return amount.signum() == 0 ? "" : DocumentFormats.money(amount);
    }
}
