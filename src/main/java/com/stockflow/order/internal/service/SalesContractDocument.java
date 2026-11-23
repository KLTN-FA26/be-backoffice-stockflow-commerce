package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.NationalHeader;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Summary;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.Letterhead;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.StringJoiner;

/**
 * Draws a sales contract as a PDF (4B-4, kltn-docs 14 §4.3 steps 11-13), in the form a Vietnamese contract for the
 * sale of goods takes: Quốc hiệu and Tiêu ngữ, the title and number, the legal bases, the two parties, then numbered
 * clauses and the two signature blocks.
 *
 * <p>The clauses cover what Bộ luật Dân sự 2015 Điều 398 lists as a contract's content - the goods, quantity and
 * quality, price and payment, time and place of performance, the parties' rights and duties, liability for breach,
 * dispute resolution - plus what a contract for goods printed to the buyer's design needs and a general one lacks:
 * the approved print sample as the standard of quality, the tolerance of a printed run's quantity, who answers for
 * the design's content, and the warranty of the print. The penalty clause prints the configured rate, which
 * {@link ContractProperties} keeps within the 8% of Luật Thương mại 2005 Điều 301.</p>
 *
 * <p>Deliveries and payments by instalment are tables inside their clauses, read from the contract's schedule and
 * plan. A clause whose fact is absent is left out and the ones after it are renumbered, so the paper never prints a
 * blank or skips a number.</p>
 *
 * <p>Every word is the Vietnamese bundle's - the contract is a Vietnamese document - and the layout is the kit's
 * ({@link BusinessDocument}), in black alone: a contract carries no brand colour. What it shows comes from the
 * contract and its order; nothing is looked up here.</p>
 */
@Component
class SalesContractDocument {

    /**
     * @param contract the contract's view; its parties are the ones written down when it was drawn up
     * @param order    its order: lines, fees, delivery address
     * @param dated    the day the document is dated
     * @param head     our letterhead, for the seller's phone, e-mail and the account the buyer pays into
     * @param names    each SKU's product name, by SKU code; a SKU without one is shown by its code
     * @param issued   whether this is the copy that goes to the customer. The copy kept when a contract is sent is
     *                 drawn while the contract is still a draft - it becomes SENT only once the file is stored - so
     *                 the status alone would stamp "draft" across the very paper the customer signs
     */
    record Facts(SalesContracts.ContractView contract, Order order, LocalDate dated, Letterhead head,
                 Map<String, String> names, boolean issued) { }

    private final DocumentFonts fonts;
    private final ContractProperties terms;

    SalesContractDocument(DocumentFonts fonts, ContractProperties terms) {
        this.fonts = fonts;
        this.terms = terms;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        SalesContracts.ContractView c = facts.contract();
        List<Block> blocks = new ArrayList<>();

        blocks.add(new NationalHeader(t.getString("document.nation"), t.getString("document.motto"), null, null,
                null));
        blocks.add(new Title(t.getString("contract.pdf.title"),
                t.getString("document.number") + " " + c.contractNumber(), null));
        for (String basis : List.of(t.getString("contract.pdf.basis.civil"), t.getString("contract.pdf.basis.trade"),
                t.getString("contract.pdf.basis.order").formatted(c.orderNumber()),
                t.getString("contract.pdf.basis.need"))) {
            blocks.add(Paragraph.italic(basis).firstIndent(28).after(1));
        }
        String date = DocumentFormats.longDate(facts.dated());
        blocks.add(Paragraph.of(SalesDocumentLines.filled(facts.head().city())
                ? t.getString("contract.pdf.todayAt").formatted(date, facts.head().city())
                : t.getString("contract.pdf.today").formatted(date)).firstIndent(28).after(6));
        SalesDocumentLines.party(blocks, t, "contract.pdf.seller", c.seller(), facts.head());
        SalesDocumentLines.party(blocks, t, "contract.pdf.buyer", c.buyer(), null);
        blocks.add(Paragraph.of(t.getString("contract.pdf.agreed")).firstIndent(28));

        Clauses clauses = new Clauses(blocks, t);
        goods(clauses, facts);
        quality(clauses, c);
        clauses.title("packing");
        clauses.point("packing.cartons");
        clauses.point("packing.labels");
        delivery(clauses, facts);
        payment(clauses, facts);
        clauses.title("invoice");
        clauses.point("invoice.each");
        clauses.point("invoice.details");
        claims(clauses, c);
        clauses.title("duties");
        clauses.point("duties.seller");
        clauses.point("duties.buyer");
        clauses.title("breach");
        if (terms.penaltyPercent() > 0) {
            clauses.point("breach.penalty", terms.penaltyPercent());
        }
        clauses.point("breach.damages");
        clauses.point("breach.interest");
        clauses.title("forceMajeure");
        clauses.point("forceMajeure.events");
        clauses.point("forceMajeure.notice");
        clauses.title("disputes");
        clauses.point("disputes.talks");
        clauses.point("disputes.court");
        clauses.title("validity");
        clauses.point("validity.from");
        clauses.point("validity.changes");
        clauses.point("validity.liquidation");
        clauses.point("validity.copies");

        String caption = t.getString("document.sign.sealed");
        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("contract.pdf.sign.seller"), SalesDocumentLines.position(c.seller()),
                        caption, c.seller().representative()),
                new Signer(t.getString("contract.pdf.sign.buyer"), SalesDocumentLines.position(c.buyer()), caption,
                        c.buyer().representative()))));

        Layout layout = Layout.official(t.getString("document.number") + " " + c.contractNumber(), null,
                t.getString("document.page"), watermark(t, facts));
        return BusinessDocument.draw(fonts, layout, t.getString("contract.pdf.title") + " " + c.contractNumber(),
                blocks);
    }

    /** A draft being previewed and a cancelled contract say so across every page; the copy sent does not. */
    private static String watermark(ResourceBundle t, Facts facts) {
        String status = facts.contract().status();
        if ("CANCELLED".equals(status)) {
            return t.getString("contract.pdf.status.CANCELLED");
        }
        return "DRAFT".equals(status) && !facts.issued() ? t.getString("contract.pdf.status.DRAFT") : null;
    }

    /** Clause 1: the goods and fee lines in a table, the contract's value under it and in words. */
    private void goods(Clauses clauses, Facts facts) {
        ResourceBundle t = clauses.t;
        SalesContracts.ContractView c = facts.contract();
        clauses.title("goods");
        clauses.point("goods.table");
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        String piece = t.getString("document.unit.piece");
        for (OrderLine line : facts.order().lines()) {
            List<String> details = new ArrayList<>();
            if (line.cartons() != null && line.unitsPerCarton() != null) {
                details.add(t.getString("document.goods.packing").formatted(DocumentFormats.number(line.cartons()),
                        DocumentFormats.number(line.unitsPerCarton())));
            }
            rows.add(SalesDocumentLines.goodsRow(++no, line.sku().code(), facts.names().get(line.sku().code()),
                    details, piece, DocumentFormats.number(line.quantity()), line.unitPrice().amount(),
                    line.lineTotal().amount(), line.taxRate() == null ? BigDecimal.ZERO : line.taxRate()));
        }
        for (OrderFee fee : facts.order().fees()) {
            rows.add(SalesDocumentLines.goodsRow(++no, fee.sku() == null ? null : fee.sku().code(),
                    t.getString("contract.pdf.fee." + fee.kind().name()), List.of(),
                    t.getString("document.unit.fee"), "1", fee.amount().amount(), fee.amount().amount(),
                    fee.taxRate() == null ? BigDecimal.ZERO : fee.taxRate()));
        }
        List<Summary> summaries = List.of(
                new Summary(t.getString("contract.pdf.net"), DocumentFormats.money(c.totals().netAmount()), false),
                new Summary(t.getString("document.total.tax"), DocumentFormats.money(c.totals().taxAmount()), false),
                new Summary(t.getString("contract.pdf.total"), DocumentFormats.money(c.totals().totalAmount()),
                        true));
        clauses.blocks.add(new Table(SalesDocumentLines.goodsColumns(t), rows, summaries,
                SalesDocumentLines.AMOUNT_COLUMN));
        SalesDocumentLines.inWords(clauses.blocks, t, "contract.pdf.inWords", c.totals().totalAmount());
        clauses.point("goods.price");
    }

    /** Clause 2: the approved sample as the standard, the free sample rounds, the tolerance, colour, the design. */
    private void quality(Clauses clauses, SalesContracts.ContractView c) {
        clauses.title("quality");
        clauses.point("quality.sample");
        clauses.point("quality.rounds", c.freeSampleRounds());
        if (terms.quantityTolerancePercent() > 0) {
            clauses.point("quality.tolerance", terms.quantityTolerancePercent());
        }
        clauses.point("quality.colour");
        clauses.point("quality.design");
    }

    /** Clause 4: where the goods go, the delivery schedule as a table, the handover record of each delivery. */
    private void delivery(Clauses clauses, Facts facts) {
        ResourceBundle t = clauses.t;
        SalesContracts.ContractView c = facts.contract();
        clauses.title("delivery");
        OrderAddressSnapshot address = facts.order().shippingAddress();
        if (address != null) {
            clauses.point("delivery.place", address(address), nonNull(address.recipientName()),
                    nonNull(address.phone()));
        }
        if (!c.deliverySchedule().isEmpty()) {
            clauses.point("delivery.schedule", c.deliverySchedule().size());
            List<List<Cell>> rows = new ArrayList<>();
            for (SalesContracts.BatchView batch : c.deliverySchedule()) {
                String window = batch.windowStart() == null ? t.getString("contract.pdf.windowPending")
                        : batch.windowStart().equals(batch.windowEnd()) ? DocumentFormats.date(batch.windowStart())
                        : t.getString("contract.pdf.window").formatted(DocumentFormats.date(batch.windowStart()),
                        DocumentFormats.date(batch.windowEnd()));
                List<String> goods = new ArrayList<>();
                for (SalesContracts.BatchLineView line : batch.lines()) {
                    goods.add(t.getString("contract.pdf.batchLine").formatted(line.sku(),
                            DocumentFormats.number(line.cartons()), DocumentFormats.number(line.units())));
                }
                rows.add(List.of(Cell.of(String.valueOf(batch.seq())), Cell.of(window),
                        Cell.of(goods.isEmpty() ? "" : goods.getFirst())
                                .details(goods.size() > 1 ? goods.subList(1, goods.size()) : List.of()),
                        Cell.of(DocumentFormats.money(batch.value()))));
            }
            clauses.blocks.add(new Table(List.of(
                    new Column(t.getString("contract.pdf.batch.no"), 36, Align.CENTRE),
                    new Column(t.getString("contract.pdf.batch.window"), 110, Align.CENTRE),
                    new Column(t.getString("contract.pdf.batch.goods"), 210, Align.LEFT),
                    new Column(t.getString("contract.pdf.batch.value"), 110, Align.RIGHT)), rows));
        }
        clauses.point("delivery.record");
        clauses.point("delivery.check");
        clauses.point("delivery.risk");
    }

    /** Clause 5: the instalments as a table, the account they are paid into, the currency. */
    private void payment(Clauses clauses, Facts facts) {
        ResourceBundle t = clauses.t;
        SalesContracts.ContractView c = facts.contract();
        Letterhead head = facts.head();
        clauses.title("payment");
        if (!c.paymentPlan().isEmpty()) {
            clauses.point("payment.plan", c.paymentPlan().size());
            List<List<Cell>> rows = new ArrayList<>();
            for (SalesContracts.InstallmentView installment : c.paymentPlan()) {
                String when = t.getString("contract.pdf.milestone." + installment.milestone())
                        .formatted(installment.dueDays() == null ? 0 : installment.dueDays());
                if (installment.deliveryBatchSeq() != null) {
                    when = when + " " + t.getString("contract.pdf.milestone.batch").formatted(
                            installment.deliveryBatchSeq());
                }
                rows.add(List.of(Cell.of(String.valueOf(installment.seq())), Cell.of(installment.name()),
                        Cell.of(when), Cell.of(installment.percent() == null ? ""
                                : DocumentFormats.rate(installment.percent())),
                        Cell.of(DocumentFormats.money(installment.amount()))));
            }
            clauses.blocks.add(new Table(List.of(
                    new Column(t.getString("contract.pdf.installment.no"), 36, Align.CENTRE),
                    new Column(t.getString("contract.pdf.installment.name"), 140, Align.LEFT),
                    new Column(t.getString("contract.pdf.installment.when"), 160, Align.LEFT),
                    new Column(t.getString("contract.pdf.installment.percent"), 44, Align.CENTRE),
                    new Column(t.getString("contract.pdf.installment.amount"), 86, Align.RIGHT)), rows,
                    List.of(new Summary(t.getString("contract.pdf.installment.total"),
                            DocumentFormats.money(c.totals().totalAmount()), true)), 4));
        }
        if (SalesDocumentLines.filled(head.bankAccountNumber()) && SalesDocumentLines.filled(head.bankName())) {
            clauses.point("payment.account", head.bankAccountNumber(), SalesDocumentLines.bank(head),
                    c.seller().name());
        } else {
            clauses.point("payment.transfer");
        }
        clauses.point("payment.currency");
    }

    /** Clause 7: the complaint period agreed (else the statute's), what happens to faulty goods, the warranty. */
    private void claims(Clauses clauses, SalesContracts.ContractView c) {
        clauses.title("claims");
        if (c.returnWindowDays() != null) {
            // Điều 318: the period to complain about a delivery is the contract's, so the contract says it.
            clauses.point("claims.window", c.returnWindowDays());
        } else {
            clauses.point("claims.statute");
        }
        clauses.point("claims.faulty");
        if (terms.warrantyMonths() > 0) {
            clauses.point("claims.warranty", terms.warrantyMonths());
        }
    }

    /**
     * Numbers the clauses and their points as they are written: "ĐIỀU 3", "3.1.", "3.2." - so a point left out for a
     * fact the contract lacks leaves no gap. The texts refer to one clause by its number only - the first, the goods,
     * which no contract lacks - so no reference can come to point at the wrong clause.
     */
    private static final class Clauses {

        private final List<Block> blocks;
        private final ResourceBundle t;
        private int clause;
        private int point;

        Clauses(List<Block> blocks, ResourceBundle t) {
            this.blocks = blocks;
            this.t = t;
        }

        void title(String key) {
            clause++;
            point = 0;
            blocks.add(new Heading(t.getString("contract.pdf.clause").formatted(clause,
                    t.getString("contract.pdf.clause." + key))));
        }

        void point(String key, Object... args) {
            point++;
            blocks.add(Paragraph.numbered(clause + "." + point + ".",
                    Run.of(t.getString("contract.pdf.clause." + key).formatted(args))).after(3));
        }
    }

    private static String address(OrderAddressSnapshot a) {
        StringJoiner joined = new StringJoiner(", ");
        for (String part : new String[] {a.line1(), a.line2(), a.wardName(), a.provinceName()}) {
            if (part != null && !part.isBlank()) {
                joined.add(part);
            }
        }
        return joined.toString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
