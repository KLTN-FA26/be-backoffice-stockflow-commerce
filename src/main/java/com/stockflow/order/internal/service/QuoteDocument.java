package com.stockflow.order.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.PrintedDocument;
import com.stockflow.common.label.PrintedDocument.Line;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Draws a quote as a PDF (kltn-docs 14 §4.1, BR-08, BR-09): the file a wholesale customer takes into its own approval
 * before it accepts - the parties, each line in cartons and pieces with its price per piece and the tiers it was
 * offered at, the fee lines, the VAT, the three totals and the total in words, the requested delivery date and the
 * terms. The parties and the words are drawn as the sales contract draws them ({@link SalesDocumentLines}), so the
 * quote and the contract it becomes read alike.
 *
 * <p>The figures are those the customer was offered - or, once it accepted, those it accepted: an accepted quote
 * prints the cartons and prices that became the order. A draft says it is one, as do a cancelled and an expired quote,
 * so no file of an offer that does not stand can pass for one. A part not known yet (a line not priced, a rate missing:
 * only a draft has one) is printed as such and its total is left without words. A quote drafted before 4B-2 on blanks
 * and designs (QD-16) counts pieces, not cartons, and is printed so.</p>
 *
 * <p>The labels are the Vietnamese bundle's - the quote is a Vietnamese document - laid out as every document of the
 * module is ({@link PrintedDocument}). What it shows is given; nothing is looked up here.</p>
 */
@Component
class QuoteDocument {

    /**
     * @param quote  as its reader may see it: a portal customer's view is the offer it was sent
     * @param seller the company that sells ({@code stockflow.seller.*})
     * @param buyer  the customer: its legal profile when recorded, else its name and tax code
     * @param dated  the day the quote was sent, or today for a draft
     */
    record Facts(Quotes.QuoteView quote, SalesContracts.Party seller, SalesContracts.Party buyer, LocalDate dated) {
    }

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);

    private final LabelFonts fonts;

    QuoteDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        Quotes.QuoteView q = facts.quote();
        boolean accepted = q.accepted() != null;
        List<Line> lines = new ArrayList<>();
        lines.add(Line.title(t.getString("quote.pdf.title")));
        if (q.status() == QuoteStatus.DRAFT || q.status() == QuoteStatus.CANCELLED
                || q.status() == QuoteStatus.EXPIRED) {
            lines.add(Line.bold(t.getString("quote.pdf.status." + q.status().name())));
        }
        lines.add(Line.text(t.getString("quote.pdf.number").formatted(q.quoteNumber(), q.revision(),
                DAY.format(facts.dated()))));
        lines.add(Line.text(t.getString("quote.pdf.validUntil").formatted(MOMENT.format(q.validUntil()))));
        if (accepted && q.respondedAt() != null) {
            lines.add(Line.text(t.getString("quote.pdf.acceptedAt").formatted(MOMENT.format(q.respondedAt()))));
        }
        SalesDocumentLines.party(lines, t, "quote.pdf.seller", facts.seller());
        SalesDocumentLines.party(lines, t, "quote.pdf.buyer", facts.buyer());

        lines.add(Line.heading(t.getString("quote.pdf.goods")));
        for (Quotes.LineView line : q.lines()) {
            goods(lines, t, line, accepted);
        }
        for (Quotes.FeeView fee : q.fees()) {
            String kind = t.getString("contract.pdf.fee." + fee.kind().name()).formatted(fee.sku() == null ? ""
                    : fee.sku());
            lines.add(Line.text(t.getString("quote.pdf.feeLine").formatted(kind, amount(t, fee.amount()),
                    vat(t, fee.taxRate(), fee.tax()))));
        }

        Quotes.Totals totals = accepted ? q.accepted() : q.proposed();
        lines.add(Line.heading(t.getString("quote.pdf.totals")));
        lines.add(Line.text(t.getString("quote.pdf.subtotal") + ": " + amount(t, totals.subtotal())));
        if (!q.fees().isEmpty()) {
            lines.add(Line.text(t.getString("quote.pdf.fees") + ": " + amount(t, totals.fees())));
        }
        BigDecimal net = totals.subtotal() == null || totals.fees() == null ? null
                : totals.subtotal().add(totals.fees());
        lines.add(Line.text(t.getString("contract.pdf.net") + ": " + amount(t, net)));
        lines.add(Line.text(t.getString("contract.pdf.tax") + ": " + amount(t, totals.tax())));
        lines.add(Line.bold(t.getString("contract.pdf.total") + ": " + amount(t, totals.total())));
        SalesDocumentLines.inWords(lines, t, totals.total());

        lines.add(Line.heading(t.getString("quote.pdf.terms")));
        if (q.requestedDeliveryDate() != null) {
            lines.add(Line.text(t.getString("quote.pdf.requestedDelivery").formatted(
                    DAY.format(q.requestedDeliveryDate()))));
        }
        lines.add(Line.text(t.getString("quote.pdf.pricing")));
        if (q.terms() != null && !q.terms().isBlank()) {
            // Sales types the terms over several lines; each is a line of the document.
            q.terms().lines().filter(part -> !part.isBlank()).forEach(part -> lines.add(Line.text(part)));
        }
        lines.add(Line.heading(""));
        lines.add(Line.bold(t.getString("quote.pdf.signature")));
        return PrintedDocument.draw(fonts, lines, t.getString("quote.pdf.title") + " " + q.quoteNumber(), null);
    }

    /** A goods line, in cartons and pieces (or pieces alone on a legacy line), then the tiers it was offered at. */
    private static void goods(List<Line> lines, ResourceBundle t, Quotes.LineView line, boolean accepted) {
        BigDecimal unitPrice = accepted ? line.acceptedUnitPrice() : line.proposedUnitPrice();
        BigDecimal net = accepted ? line.acceptedLineTotal() : line.proposedLineTotal();
        BigDecimal tax = accepted ? line.acceptedTax() : line.proposedTax();
        if ("CARTON".equals(line.measure())) {
            int cartons = accepted && line.acceptedCartons() != null ? line.acceptedCartons()
                    : line.cartons() == null ? 0 : line.cartons();
            int perCarton = line.unitsPerCarton() == null ? 0 : line.unitsPerCarton();
            int units = accepted && line.acceptedUnits() != null ? line.acceptedUnits() : line.units();
            lines.add(Line.text(t.getString("quote.pdf.line").formatted(line.sku(), cartons, perCarton, units,
                    amount(t, unitPrice), amount(t, net), vat(t, line.taxRate(), tax))));
        } else {
            int units = accepted && line.acceptedUnits() != null ? line.acceptedUnits() : line.units();
            lines.add(Line.text(t.getString("quote.pdf.legacyLine").formatted(line.sku(), units,
                    amount(t, unitPrice), amount(t, net), vat(t, line.taxRate(), tax))));
        }
        if (!line.tiers().isEmpty()) {
            String tier = t.getString("quote.pdf.tier." + ("CARTON".equals(line.measure()) ? "CARTON" : "UNIT"));
            lines.add(Line.text(t.getString("quote.pdf.tiers").formatted(line.tiers().stream()
                    .map(each -> tier.formatted(each.minQuantity(), SalesDocumentLines.money(each.unitPrice())))
                    .collect(Collectors.joining("; ")))));
        }
    }

    /** The amount, or "not set yet" for a part of a draft that is not priced. */
    private static String amount(ResourceBundle t, BigDecimal amount) {
        return amount == null ? t.getString("quote.pdf.pending") : SalesDocumentLines.money(amount);
    }

    /**
     * "10% = 9.000 VND"; "not set yet" while the tax is not known (a draft's line without its rate or price); the tax
     * alone where it is known without a rate - a line offered before VAT was kept (4B-3), whose tax is nil.
     */
    private static String vat(ResourceBundle t, BigDecimal rate, BigDecimal tax) {
        if (tax == null) {
            return t.getString("quote.pdf.pending");
        }
        return rate == null ? SalesDocumentLines.money(tax)
                : t.getString("quote.pdf.vat").formatted(SalesDocumentLines.rate(rate), SalesDocumentLines.money(tax));
    }
}
