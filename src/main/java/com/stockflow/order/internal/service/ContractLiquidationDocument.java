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
 * money a table from the contract's value down to what is still owed, each payment with its date and reference. The
 * balance is worked out here from the figures printed above it, so the record cannot state a balance its own rows do
 * not add up to.</p>
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
     * @param reason       why the contract ends before it is performed; null for a contract performed in full
     * @param settledValue the value of what was actually delivered
     * @param deductions   what returns and invoice adjustments took off that value; zero when none
     * @param refunded     what the seller has already paid back; zero when none
     * @param invoices     the invoices issued under the contract, adjustments included, as "number (date)"
     * @param settleBy     the day the balance, if any, is to be paid by; null to leave the day to be written in
     */
    record Facts(String number, String contractNumber, LocalDate contractDate, LocalDate dated,
                 SalesContracts.Party seller, SalesContracts.Party buyer, String reason, List<GoodsLine> goods,
                 BigDecimal contractValue, BigDecimal settledValue, BigDecimal deductions, List<Payment> payments,
                 BigDecimal refunded, List<String> invoices, LocalDate settleBy) {

        /** What the buyer still owes; below zero, what the seller still owes back. */
        BigDecimal balance() {
            BigDecimal paid = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return settledValue.subtract(deductions).subtract(paid).add(refunded);
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

    byte[] render(Facts f) {
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
        BigDecimal balance = f.balance();
        blocks.add(settlement(t, f, balance));
        if (balance.signum() != 0) {
            DocumentParts.inWords(blocks, t.getString("document.inWords"), balance.abs());
        }
        if (!f.invoices().isEmpty()) {
            clauses.point("done.invoices", String.join("; ", f.invoices()));
        }

        clauses.title("remaining");
        int remaining = clauses.number();
        if (balance.signum() != 0) {
            clauses.point(balance.signum() > 0 ? "remaining.pay" : "remaining.refund",
                    DocumentFormats.money(balance.abs()), f.settleBy() == null ? t.getString("document.blankDate")
                            : DocumentFormats.date(f.settleBy()));
        } else {
            clauses.point("remaining.none");
        }
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

    /** From the contract's value down to the balance: every figure the balance is made of, then the balance. */
    private static Table settlement(ResourceBundle t, Facts f, BigDecimal balance) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row(t.getString("liquidation.pdf.settle.contract"), "", f.contractValue(), false));
        rows.add(row(t.getString("liquidation.pdf.settle.settled"), "", f.settledValue(), false));
        if (f.deductions().signum() != 0) {
            rows.add(row(t.getString("liquidation.pdf.settle.deductions"), "", f.deductions().negate(), false));
        }
        for (Payment payment : f.payments()) {
            rows.add(row(t.getString("liquidation.pdf.settle.paid").formatted(DocumentFormats.date(payment.date())),
                    payment.reference(), payment.amount().negate(), false));
        }
        if (f.refunded().signum() != 0) {
            rows.add(row(t.getString("liquidation.pdf.settle.refunded"), "", f.refunded(), false));
        }
        rows.add(row(t.getString(balance.signum() > 0 ? "liquidation.pdf.settle.due" : balance.signum() < 0
                ? "liquidation.pdf.settle.refund" : "liquidation.pdf.settle.none"), "", balance.abs(), true));
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
