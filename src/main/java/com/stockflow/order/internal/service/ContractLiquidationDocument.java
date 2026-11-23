package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
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
 * Draws the record that liquidates a sales contract ("biên bản thanh lý hợp đồng") as a PDF: what the two parties sign
 * when a contract ends - performed in full, or ended early by agreement (Bộ luật Dân sự 2015 Điều 422) - to state that
 * neither owes the other anything more, or exactly what one still owes.
 *
 * <p>No law requires it or fixes its form; it is the evidence that the obligations are closed, so it is set as the
 * contract was - Quốc hiệu and Tiêu ngữ, the legal bases, the two parties, four clauses, two signature blocks - and
 * its second clause is a settlement, not a sentence: a contract here is delivered in several batches and paid in
 * several instalments, so the goods are a table of what was contracted, delivered and returned line by line, and the
 * money a table the owner fixed (2026-11-23): the contract's value as signed, never amended here; the value performed
 * - the goods delivered successfully and kept, 0 when nothing was; what was paid; what was paid back; and what is left,
 * which is 0, followed by the statement that the parties owe each other nothing more. Fees the contract charged besides
 * the goods (plate fees, the shipping fee kept, a cancellation fee) are a row of their own between the value performed
 * and the payments, so the rows add up.</p>
 *
 * <p><b>Only a settled contract is liquidated here.</b> A record whose rows leave something to pay or to pay back is
 * a wrong paper to sign - it would say "no other obligations" over a debt - so {@link #render} refuses one whose
 * rows do not come to 0, and its caller refuses before it asks ({@code ContractPaperService}).</p>
 *
 * <p>A contract that ends with nothing delivered - every delivery failed, or it was called off after signing - is
 * liquidated too (owner decision Q-6: such a contract is "liquidated", not "completed"): the goods table is then a
 * sentence saying nothing was delivered, and the first clause gives the reason. The words are the Vietnamese bundle's,
 * the layout the kit's ({@link BusinessDocument}) in black; what it shows is given, nothing is looked up here.</p>
 */
@Component
class ContractLiquidationDocument {

    /**
     * Everything the record says. Amounts include VAT, as the contract's value does.
     *
     * @param reason         why the contract ends before it is performed; null for a contract performed in full
     * @param contractValue  the contract's value as signed - never the value performed
     * @param performedValue the goods delivered successfully and kept (delivered less returned), with VAT; zero when
     *                       nothing was delivered
     * @param otherCharges   what the contract charged besides those goods and the customer keeps paying for - plate
     *                       fees, the shipping fee not waived, a cancellation or closing fee; below zero, a reduction
     *                       the books made that the goods do not show; zero when none
     * @param refunded       what the seller paid back; zero when none
     * @param invoices       the invoices issued under the contract, adjustments included, as "number (date)"
     */
    record Facts(String number, String contractNumber, LocalDate contractDate, LocalDate dated,
                 SalesContracts.Party seller, SalesContracts.Party buyer, String reason, List<GoodsLine> goods,
                 BigDecimal contractValue, BigDecimal performedValue, BigDecimal otherCharges, List<Payment> payments,
                 BigDecimal refunded, List<String> invoices) {

        /** What the buyer paid, over every payment in force. */
        BigDecimal paid() {
            return payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** What the rows leave: owed for the value performed and the charges, less paid, plus paid back. */
        BigDecimal remaining() {
            return performedValue.add(otherCharges).subtract(paid()).add(refunded);
        }
    }

    /** A line of the contract's goods: contracted, delivered over all batches, returned. */
    record GoodsLine(String code, String name, String unit, long contracted, long delivered, long returned,
                     BigDecimal unitPrice) {

        long settled() {
            return delivered - returned;
        }
    }

    /** A payment received: its day, the reference of the bank transfer or receipt, the amount. */
    record Payment(LocalDate date, String reference, BigDecimal amount) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    ContractLiquidationDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    /**
     * @throws IllegalArgumentException for facts whose rows leave something to pay or to pay back: a liquidation
     *         states that nothing is left, and this one would not be true
     */
    byte[] render(Facts f) {
        if (f.remaining().signum() != 0) {
            throw new IllegalArgumentException(("Contract %s is not settled: its rows leave %s; a liquidation is "
                    + "drawn only for a contract with nothing left to pay or pay back").formatted(f.contractNumber(),
                    f.remaining().toPlainString()));
        }
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        String contractDate = DocumentFormats.date(f.contractDate());
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.nationalHead(t));
        String title = t.getString("liquidation.pdf.title");
        blocks.add(new Title(title, t.getString("document.number") + " " + f.number(), null));
        blocks.add(DocumentParts.basis(t.getString("contract.pdf.basis.civil")));
        blocks.add(DocumentParts.basis(t.getString("contract.pdf.basis.trade")));
        blocks.add(DocumentParts.basis(t.getString("liquidation.pdf.basis.contract").formatted(f.contractNumber(),
                contractDate)));
        blocks.add(DocumentParts.basis(t.getString("liquidation.pdf.basis.records")));
        blocks.add(DocumentParts.today(t, head, f.dated()));
        SalesDocumentLines.party(blocks, t, "contract.pdf.seller", f.seller(), head);
        SalesDocumentLines.party(blocks, t, "contract.pdf.buyer", f.buyer(), null);
        blocks.add(DocumentParts.lead(t.getString("liquidation.pdf.agreed")));

        DocumentClauses clauses = new DocumentClauses(blocks, t, "liquidation.pdf");
        clauses.title("end");
        if (DocumentParts.filled(f.reason())) {
            clauses.point("end.reason", f.reason(), f.contractNumber());
        } else {
            clauses.point("end.done", f.contractNumber());
        }

        clauses.title("done");
        boolean delivered = f.goods().stream().anyMatch(line -> line.delivered() > 0);
        if (delivered) {
            clauses.point("done.goods");
            blocks.add(goods(t, f.goods()));
        } else {
            clauses.point("done.noGoods");
        }
        clauses.point("done.payment");
        blocks.add(settlement(t, f));
        blocks.add(DocumentParts.lead(t.getString("liquidation.pdf.settle.noObligation")));
        if (!f.invoices().isEmpty()) {
            clauses.point("done.invoices", String.join("; ", f.invoices()));
        }

        clauses.title("remaining");
        int remaining = clauses.number();
        clauses.point("remaining.none");
        if (delivered) {
            clauses.point("remaining.warranty");
        }

        clauses.title("general");
        clauses.point("general.none", remaining);
        clauses.point("general.copies");

        blocks.add(new Signatures(null, List.of(
                DocumentParts.signatory(t, t.getString("contract.pdf.sign.seller"),
                        SalesDocumentLines.party(t, f.seller(), null)),
                DocumentParts.signatory(t, t.getString("contract.pdf.sign.buyer"),
                        SalesDocumentLines.party(t, f.buyer(), null)))));
        return BusinessDocument.draw(fonts, DocumentParts.official(t, f.number(), null), title + " " + f.number(),
                blocks);
    }

    /** What was contracted, delivered and returned, line by line; the amount is of what was kept, before VAT. */
    private static Table goods(ResourceBundle t, List<GoodsLine> goods) {
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        for (GoodsLine line : goods) {
            rows.add(List.of(Cell.of(String.valueOf(++no)),
                    Cell.bold(DocumentParts.filled(line.name()) ? line.name() : line.code())
                            .details(DocumentParts.filled(line.name()) ? List.of(line.code()) : List.of()),
                    Cell.of(line.unit()), Cell.of(DocumentFormats.number(line.contracted())),
                    Cell.of(DocumentFormats.number(line.delivered())),
                    Cell.of(DocumentFormats.number(line.returned())),
                    Cell.of(DocumentFormats.number(line.settled())),
                    Cell.of(DocumentFormats.money(line.unitPrice())),
                    Cell.of(DocumentFormats.money(line.unitPrice().multiply(BigDecimal.valueOf(line.settled()))))));
        }
        return new Table(List.of(new Column(t.getString("document.goods.no"), 26, Align.CENTRE),
                new Column(t.getString("document.goods.name"), 120, Align.LEFT),
                new Column(t.getString("document.goods.unit"), 32, Align.CENTRE),
                new Column(t.getString("liquidation.pdf.col.contracted"), 50, Align.RIGHT),
                new Column(t.getString("liquidation.pdf.col.delivered"), 46, Align.RIGHT),
                new Column(t.getString("liquidation.pdf.col.returned"), 42, Align.RIGHT),
                new Column(t.getString("liquidation.pdf.col.settled"), 50, Align.RIGHT),
                new Column(t.getString("liquidation.pdf.col.unitPrice"), 50, Align.RIGHT),
                new Column(t.getString("liquidation.pdf.col.amount"), 70, Align.RIGHT)), rows);
    }

    /**
     * The owner's rows (2026-11-23), every figure positive: the contract's value, the value performed, the other
     * charges when there are any, what was paid - with each payment under it - what was paid back, and what is left,
     * which is 0.
     */
    private static Table settlement(ResourceBundle t, Facts f) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row(t.getString("liquidation.pdf.settle.contract"), "", f.contractValue(), false));
        rows.add(row(t.getString("liquidation.pdf.settle.performed"), "", f.performedValue(), false));
        if (f.otherCharges().signum() != 0) {
            rows.add(row(t.getString(f.otherCharges().signum() > 0 ? "liquidation.pdf.settle.other"
                    : "liquidation.pdf.settle.otherReduction"), "", f.otherCharges().abs(), false));
        }
        rows.add(row(t.getString("liquidation.pdf.settle.paid"), "", f.paid(), false));
        for (Payment payment : f.payments()) {
            rows.add(row(t.getString("liquidation.pdf.settle.payment").formatted(DocumentFormats.date(payment.date())),
                    payment.reference(), payment.amount(), false));
        }
        rows.add(row(t.getString("liquidation.pdf.settle.refunded"), "", f.refunded(), false));
        rows.add(row(t.getString("liquidation.pdf.settle.remaining"), "", f.remaining(), true));
        return new Table(List.of(new Column(t.getString("liquidation.pdf.col.item"), 250, Align.LEFT),
                new Column(t.getString("liquidation.pdf.col.reference"), 110, Align.LEFT),
                new Column(t.getString("liquidation.pdf.col.money"), 106, Align.RIGHT)), rows);
    }

    private static List<Cell> row(String item, String reference, BigDecimal amount, boolean bold) {
        String money = DocumentFormats.money(amount);
        return List.of(bold ? Cell.bold(item) : Cell.of(item), Cell.of(reference == null ? "" : reference),
                bold ? Cell.bold(money) : Cell.of(money));
    }
}
