package com.stockflow.order.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.StringJoiner;

/**
 * Draws a sales contract as a PDF (4B-4, kltn-docs 14 §4.3 steps 11-13): the parties, the goods and fee lines with
 * their VAT, the three totals, the delivery terms and schedule, the payment plan and the free print-sample rounds. The
 * labels are the Vietnamese bundle's - the contract is a Vietnamese document - and the text is drawn in the Noto Sans
 * the labels use, which has the Vietnamese glyphs. What it shows comes from the contract and its order; nothing is
 * looked up here.
 */
@Component
class SalesContractDocument {

    /** The contract's view, its order (lines, fees, address) and the day the document is dated. */
    record Facts(SalesContracts.ContractView contract, Order order, LocalDate dated) { }

    private static final float MARGIN = 50;
    private static final float LINE = 14;
    private static final int WRAP = 95;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LabelFonts fonts;

    SalesContractDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        SalesContracts.ContractView c = facts.contract();
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Style.TITLE, t.getString("contract.pdf.title")));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.number") + ": " + c.contractNumber() + "    "
                + t.getString("contract.pdf.date") + ": " + DAY.format(facts.dated())));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.order") + ": " + c.orderNumber()));
        party(lines, t, "contract.pdf.seller", c.seller());
        party(lines, t, "contract.pdf.buyer", c.buyer());

        lines.add(new Line(Style.HEADING, t.getString("contract.pdf.goods")));
        for (OrderLine line : facts.order().lines()) {
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.goodsLine").formatted(line.sku().code(),
                    line.cartons() == null ? 0 : line.cartons(),
                    line.unitsPerCarton() == null ? 0 : line.unitsPerCarton(), line.quantity(),
                    money(line.unitPrice().amount()), money(line.lineTotal().amount()), rate(line.taxRate()),
                    money(line.taxAmount() == null ? BigDecimal.ZERO : line.taxAmount().amount()))));
        }
        for (OrderFee fee : facts.order().fees()) {
            String kind = t.getString("contract.pdf.fee." + fee.kind().name())
                    .formatted(fee.sku() == null ? "" : fee.sku().code());
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.feeLine").formatted(kind,
                    money(fee.amount().amount()), rate(fee.taxRate()), money(fee.taxAmount().amount()))));
        }

        lines.add(new Line(Style.HEADING, t.getString("contract.pdf.totals")));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.net") + ": " + money(c.totals().netAmount())));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.tax") + ": " + money(c.totals().taxAmount())));
        lines.add(new Line(Style.BOLD, t.getString("contract.pdf.total") + ": " + money(c.totals().totalAmount())));

        lines.add(new Line(Style.HEADING, t.getString("contract.pdf.delivery")));
        OrderAddressSnapshot address = facts.order().shippingAddress();
        if (address != null) {
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.deliveryAddress") + ": " + address(address)));
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.recipient") + ": "
                    + nonNull(address.recipientName()) + " - " + nonNull(address.phone())));
        }
        for (SalesContracts.BatchView batch : c.deliverySchedule()) {
            String window = batch.windowStart() == null ? t.getString("contract.pdf.windowPending")
                    : t.getString("contract.pdf.window").formatted(DAY.format(batch.windowStart()),
                    DAY.format(batch.windowEnd()));
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.batch").formatted(batch.seq(), window,
                    money(batch.value()))));
            for (SalesContracts.BatchLineView line : batch.lines()) {
                lines.add(new Line(Style.TEXT, t.getString("contract.pdf.batchLine").formatted(line.sku(),
                        line.cartons(), line.units())));
            }
        }

        lines.add(new Line(Style.HEADING, t.getString("contract.pdf.payment")));
        for (SalesContracts.InstallmentView installment : c.paymentPlan()) {
            String when = t.getString("contract.pdf.milestone." + installment.milestone())
                    .formatted(installment.dueDays() == null ? 0 : installment.dueDays());
            if (installment.deliveryBatchSeq() != null) {
                when = when + " " + t.getString("contract.pdf.milestone.batch").formatted(installment.deliveryBatchSeq());
            }
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.installment").formatted(installment.seq(),
                    installment.name(), money(installment.amount()), when)));
        }

        lines.add(new Line(Style.HEADING, t.getString("contract.pdf.samples")));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.samplesText").formatted(c.freeSampleRounds())));
        lines.add(new Line(Style.HEADING, ""));
        lines.add(new Line(Style.BOLD, t.getString("contract.pdf.signatures")));
        return draw(lines, t.getString("contract.pdf.title") + " " + c.contractNumber());
    }

    private enum Style { TITLE, HEADING, BOLD, TEXT }

    private record Line(Style style, String text) { }

    private static void party(List<Line> lines, ResourceBundle t, String heading, SalesContracts.Party party) {
        lines.add(new Line(Style.HEADING, t.getString(heading)));
        lines.add(new Line(Style.BOLD, party.name()));
        if (party.taxCode() != null) {
            lines.add(new Line(Style.TEXT, t.getString("contract.pdf.taxCode") + ": " + party.taxCode()));
        }
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.address") + ": " + party.address()));
        lines.add(new Line(Style.TEXT, t.getString("contract.pdf.representative") + ": " + party.representative()
                + " - " + party.representativeTitle()));
    }

    /** The lines on as many A4 pages as they need, each wrapped at the page's width. */
    private byte[] draw(List<Line> lines, String title) {
        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            PDPage page = null;
            PDPageContentStream out = null;
            float y = 0;
            try {
                for (Line line : lines) {
                    PDFont font = line.style() == Style.TEXT ? regular : bold;
                    float size = switch (line.style()) {
                        case TITLE -> 16;
                        case HEADING -> 11;
                        case BOLD, TEXT -> 10;
                    };
                    List<String> parts = wrap(fonts.drawable(line.text()), line.style() == Style.TITLE ? 60 : WRAP);
                    float before = line.style() == Style.HEADING ? LINE / 2 : 0;
                    for (String part : parts) {
                        if (out == null || y - before < MARGIN) {
                            if (out != null) {
                                out.close();
                            }
                            page = new PDPage(PDRectangle.A4);
                            document.addPage(page);
                            out = new PDPageContentStream(document, page);
                            y = PDRectangle.A4.getHeight() - MARGIN;
                        }
                        y -= before;
                        before = 0;
                        out.beginText();
                        out.setFont(font, size);
                        out.newLineAtOffset(MARGIN, y);
                        out.showText(part);
                        out.endText();
                        y -= line.style() == Style.TITLE ? LINE * 1.6f : LINE;
                    }
                }
            } finally {
                if (out != null) {
                    out.close();
                }
            }
            document.getDocumentInformation().setTitle(fonts.drawable(title));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not draw the sales contract", ex);
        }
    }

    /** {@code text} cut into lines of at most {@code width} characters, at spaces where it can. */
    private static List<String> wrap(String text, int width) {
        List<String> parts = new ArrayList<>();
        String rest = text == null ? "" : text;
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut <= 0) {
                cut = width;
            }
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).strip();
        }
        parts.add(rest);
        return parts;
    }

    /** Whole dong, grouped with dots as Vietnamese documents write it: 1.234.567 VND. */
    private static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        return format.format(amount == null ? BigDecimal.ZERO : amount) + " VND";
    }

    private static String rate(BigDecimal rate) {
        return rate == null ? "0" : rate.stripTrailingZeros().toPlainString();
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
