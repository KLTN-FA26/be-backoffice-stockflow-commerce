package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Split;
import com.stockflow.common.document.BusinessDocument.Summary;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.Letterhead;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Draws a quotation as a PDF (kltn-docs 14 §4.1, BR-08, BR-09): the paper a wholesale customer takes into its own
 * approval before it accepts. It is laid out as Vietnamese companies send one: our letterhead, the number and the
 * place and date, "BẢNG BÁO GIÁ", the customer it is addressed to, the goods in a ruled table - name and
 * specification, unit, quantity, unit price, amount, VAT rate - with the totals under it and the total in words, the
 * commercial terms, and a block for each side to sign: a quotation signed back by the customer is how many of them
 * place the order.
 *
 * <p>The figures are those the customer was offered - or, once it accepted, those it accepted: an accepted quote
 * prints the cartons and prices that became the order. The terms are the company's standing ones
 * ({@link ContractProperties}) followed by what Sales typed for this quote.</p>
 *
 * <p>A quote that does not stand cannot pass for one that does: a draft, a cancelled and an expired quote carry their
 * state across every page as a watermark. Only a draft can have a line without a price (a quote is sent once every
 * line is priced): such a line shows a dash, the totals it would make false are left out, and a note says the prices
 * follow - a real company does not issue "price to be determined", so neither does this paper pretend to.</p>
 *
 * <p>Every word is the Vietnamese bundle's - the quotation is a Vietnamese document - and the layout is the kit's
 * ({@link BusinessDocument}). What it shows is given; nothing is looked up here.</p>
 */
@Component
class QuoteDocument {

    /**
     * @param quote  as its reader may see it: a portal customer's view is the offer it was sent
     * @param seller the company that sells ({@code stockflow.seller.*})
     * @param buyer  the customer: its legal profile when recorded, else its name and tax code
     * @param dated  the day the quote was sent, or today for a draft
     * @param head   our letterhead: logo, contacts, the account we are paid into
     * @param names  each SKU's product name, by SKU code; a SKU without one is shown by its code
     */
    record Facts(Quotes.QuoteView quote, SalesContracts.Party seller, SalesContracts.Party buyer, LocalDate dated,
                 Letterhead head, Map<String, String> names) {
    }

    private final DocumentFonts fonts;
    private final ContractProperties terms;

    QuoteDocument(DocumentFonts fonts, ContractProperties terms) {
        this.fonts = fonts;
        this.terms = terms;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        Quotes.QuoteView q = facts.quote();
        boolean accepted = q.accepted() != null;
        Quotes.Totals totals = accepted ? q.accepted() : q.proposed();
        List<Block> blocks = new ArrayList<>();

        blocks.add(SalesDocumentLines.brandHeader(t, facts.head()));
        List<Run> number = new ArrayList<>(List.of(Run.of(t.getString("document.number") + " "),
                Run.bold(q.quoteNumber())));
        if (q.revision() > 1) {
            number.add(Run.of(" " + t.getString("quote.pdf.revision").formatted(q.revision())));
        }
        blocks.add(new Split(number, List.of(Run.italic(SalesDocumentLines.placeDate(t, facts.head(),
                facts.dated())))));
        blocks.add(new Title(t.getString("quote.pdf.title"), null, t.getString("quote.pdf.subject")));

        SalesDocumentLines.party(blocks, t, "quote.pdf.to", facts.buyer(), null);
        blocks.add(Paragraph.of(t.getString("quote.pdf.opening").formatted(facts.seller().name())).firstIndent(20));

        blocks.add(goods(t, q, facts.names(), accepted, totals));
        if (totals.total() != null) {
            SalesDocumentLines.inWords(blocks, t, "document.inWords", totals.total());
        } else {
            blocks.add(Paragraph.italic(t.getString("quote.pdf.unpriced")));
        }
        if (accepted && q.respondedAt() != null) {
            blocks.add(Paragraph.bold(t.getString("quote.pdf.acceptedAt").formatted(
                    DocumentFormats.moment(q.respondedAt()))));
        }

        blocks.add(new Heading(t.getString("quote.pdf.terms")));
        int no = 0;
        for (String term : terms(t, facts)) {
            blocks.add(Paragraph.numbered(++no + ".", Run.of(term)).after(2));
        }
        if (SalesDocumentLines.filled(q.terms())) {
            blocks.add(Paragraph.numbered(++no + ".", Run.of(t.getString("quote.pdf.term.other"))).after(2));
            // Sales types the terms over several lines; each is a line of the document.
            q.terms().lines().filter(part -> !part.isBlank()).forEach(part ->
                    blocks.add(Paragraph.numbered("-", Run.of(part.strip())).indent(20).after(2)));
        }

        blocks.add(Paragraph.of(t.getString("quote.pdf.closing")).firstIndent(20).after(0));
        String caption = t.getString("document.sign.sealed");
        blocks.add(new Signatures(null, List.of(new Signer(t.getString("quote.pdf.sign.buyer"), caption, null),
                new Signer(t.getString("quote.pdf.sign.seller"), SalesDocumentLines.position(facts.seller()),
                        caption, facts.seller().representative()))));

        Layout layout = Layout.commercial(SalesDocumentLines.ACCENT,
                t.getString("document.number") + " " + q.quoteNumber(), facts.head().name(),
                t.getString("document.page"), watermark(t, q.status()));
        return BusinessDocument.draw(fonts, layout, t.getString("quote.pdf.title") + " " + q.quoteNumber(), blocks);
    }

    /** What is written across a quote that does not stand; null for one that does. */
    private static String watermark(ResourceBundle t, QuoteStatus status) {
        return switch (status) {
            case DRAFT, CANCELLED, EXPIRED -> t.getString("quote.pdf.status." + status.name());
            default -> null;
        };
    }

    /** The goods and the fee lines, then the totals - those that are known. */
    private static Table goods(ResourceBundle t, Quotes.QuoteView q, Map<String, String> names, boolean accepted,
                               Quotes.Totals totals) {
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        String piece = t.getString("document.unit.piece");
        for (Quotes.LineView line : q.lines()) {
            BigDecimal unitPrice = accepted ? line.acceptedUnitPrice() : line.proposedUnitPrice();
            BigDecimal net = accepted ? line.acceptedLineTotal() : line.proposedLineTotal();
            int units = accepted && line.acceptedUnits() != null ? line.acceptedUnits() : line.units();
            boolean cartons = "CARTON".equals(line.measure());
            List<String> details = new ArrayList<>();
            if (cartons) {
                int count = accepted && line.acceptedCartons() != null ? line.acceptedCartons()
                        : line.cartons() == null ? 0 : line.cartons();
                details.add(t.getString("document.goods.packing").formatted(DocumentFormats.number(count),
                        DocumentFormats.number(line.unitsPerCarton() == null ? 0 : line.unitsPerCarton())));
            }
            if (SalesDocumentLines.filled(line.designName())) {
                details.add(t.getString("document.goods.design").formatted(line.designName()));
            }
            if (SalesDocumentLines.filled(line.printSpec())) {
                details.add(t.getString("document.goods.print").formatted(line.printSpec()));
            }
            if (!line.tiers().isEmpty()) {
                String tier = t.getString("quote.pdf.tier." + (cartons ? "CARTON" : "UNIT"));
                details.add(t.getString("quote.pdf.tiers").formatted(line.tiers().stream()
                        .map(each -> tier.formatted(DocumentFormats.number(each.minQuantity()),
                                DocumentFormats.money(each.unitPrice())))
                        .collect(Collectors.joining("; "))));
            }
            rows.add(SalesDocumentLines.goodsRow(++no, line.sku(), names.get(line.sku()), details, piece,
                    DocumentFormats.number(units), unitPrice, net, line.taxRate()));
        }
        for (Quotes.FeeView fee : q.fees()) {
            String kind = t.getString("contract.pdf.fee." + fee.kind().name());
            rows.add(SalesDocumentLines.goodsRow(++no, fee.sku(), kind, List.of(),
                    t.getString("document.unit.fee"), "1", fee.amount(), fee.amount(), fee.taxRate()));
        }
        List<Summary> summaries = new ArrayList<>();
        // A total is printed only when every part of it is known: a sum over lines without prices would be a figure
        // the customer could hold us to.
        if (totals.total() != null) {
            summaries.add(new Summary(t.getString("quote.pdf.subtotal"),
                    DocumentFormats.money(totals.subtotal()), false));
            if (!q.fees().isEmpty()) {
                summaries.add(new Summary(t.getString("quote.pdf.fees"), DocumentFormats.money(totals.fees()),
                        false));
            }
            summaries.add(new Summary(t.getString("document.total.tax"), DocumentFormats.money(totals.tax()),
                    false));
            summaries.add(new Summary(t.getString("quote.pdf.total"), DocumentFormats.money(totals.total()), true));
        }
        return new Table(SalesDocumentLines.goodsColumns(t), rows, summaries, SalesDocumentLines.AMOUNT_COLUMN);
    }

    /** The commercial terms, in the order a buyer reads them: price, validity, payment, delivery, the print itself. */
    private List<String> terms(ResourceBundle t, Facts facts) {
        Quotes.QuoteView q = facts.quote();
        Letterhead head = facts.head();
        List<String> list = new ArrayList<>();
        list.add(t.getString("quote.pdf.term.price"));
        list.add(t.getString("quote.pdf.term.validity").formatted(DocumentFormats.moment(q.validUntil())));
        boolean account = SalesDocumentLines.filled(head.bankAccountNumber())
                && SalesDocumentLines.filled(head.bankName());
        list.add(account ? t.getString("quote.pdf.term.paymentAccount").formatted(head.bankAccountNumber(),
                SalesDocumentLines.bank(head), facts.seller().name())
                : t.getString("quote.pdf.term.payment"));
        list.add(q.requestedDeliveryDate() == null ? t.getString("quote.pdf.term.delivery")
                : t.getString("quote.pdf.term.deliveryOn").formatted(
                DocumentFormats.date(q.requestedDeliveryDate())));
        if (terms.quantityTolerancePercent() > 0) {
            list.add(t.getString("quote.pdf.term.tolerance").formatted(terms.quantityTolerancePercent()));
        }
        list.add(t.getString("quote.pdf.term.colour"));
        if (terms.warrantyMonths() > 0) {
            list.add(t.getString("quote.pdf.term.warranty").formatted(terms.warrantyMonths()));
        }
        list.add(t.getString("quote.pdf.term.design"));
        return list;
    }
}
