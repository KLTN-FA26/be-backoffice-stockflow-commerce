package com.stockflow.design.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/**
 * The production instruction issued with an approved design (kltn-docs 12 §4.5; plan D2): one A4 page for the
 * producer, generated from what the system knows - the customer's SKU, the variant and its carton spec, the template's
 * print areas and colour mode, the layout's checksum and who approved it - rather than drawn by the customer.
 *
 * <p>Vietnamese, as the producer reads it; the text lives in the {@code design.instruction.*} keys of the message
 * bundle, the code stays English. Drawn with the embedded Noto Sans of the labels ({@link LabelFonts}): the 14
 * standard PDF fonts cannot draw Vietnamese.</p>
 */
@Component
class ProductionInstructionSheet {

    private static final float MARGIN = 50;
    private static final float LINE = 16;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final LabelFonts fonts;

    ProductionInstructionSheet(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /** What the sheet states. {@code carton} is null when the variant has none (preflight refuses that already). */
    record Facts(String customerSku, String productName, String sourceSku, PrintConfig template,
                 UomConversion carton, String layoutChecksum, String printFileChecksum, UUID approvedBy,
                 Instant approvedAt) {
    }

    byte[] render(Facts facts) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<String[]> lines = new ArrayList<>();
        lines.add(row(texts, "design.instruction.customerSku", facts.customerSku()));
        lines.add(row(texts, "design.instruction.product", facts.productName()));
        lines.add(row(texts, "design.instruction.variant", facts.sourceSku()));
        lines.add(row(texts, "design.instruction.template", facts.template().code() + " - " + facts.template().name()));
        lines.add(row(texts, "design.instruction.colorMode", facts.template().colorMode()
                + " · " + facts.template().minDpi() + " DPI"));
        if (facts.carton() != null) {
            UomConversion carton = facts.carton();
            lines.add(row(texts, "design.instruction.carton", texts.getString("design.instruction.cartonValue")
                    .formatted(carton.unitOfMeasure(), carton.factor(), plain(carton.lengthCm()),
                            plain(carton.widthCm()), plain(carton.heightCm()), plain(carton.weightKg()))));
        }
        for (PrintArea area : facts.template().printAreas()) {
            lines.add(row(texts, "design.instruction.area", texts.getString("design.instruction.areaValue")
                    .formatted(area.code(), area.name(), area.widthMm(), area.heightMm(), area.bleedMm(),
                            area.safeMarginMm())));
        }
        lines.add(row(texts, "design.instruction.layoutChecksum", facts.layoutChecksum()));
        lines.add(row(texts, "design.instruction.printFileChecksum", facts.printFileChecksum()));
        lines.add(row(texts, "design.instruction.approved", facts.approvedBy() + " · "
                + WHEN.format(facts.approvedAt())));
        lines.add(new String[] {"", texts.getString("design.instruction.pdfx")});

        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float y = PDRectangle.A4.getHeight() - MARGIN;
            try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                y = text(out, bold, 16, MARGIN, y, texts.getString("design.instruction.title")) - LINE;
                for (String[] line : lines) {
                    if (!line[0].isEmpty()) {
                        text(out, bold, 10, MARGIN, y, line[0]);
                    }
                    for (String part : wrap(line[1], 70)) {
                        y = text(out, regular, 10, MARGIN + 150, y, part);
                    }
                }
            }
            document.getDocumentInformation().setTitle(texts.getString("design.instruction.title") + " "
                    + facts.customerSku());
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not render the production instruction", ex);
        }
    }

    private String[] row(ResourceBundle texts, String key, String value) {
        return new String[] {texts.getString(key), fonts.drawable(value == null ? "-" : value)};
    }

    /** Draws one line and returns the baseline of the next. */
    private static float text(PDPageContentStream out, PDFont font, float size, float x, float y, String text)
            throws IOException {
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(x, y);
        out.showText(text);
        out.endText();
        return y - LINE;
    }

    /** {@code text} cut into lines of at most {@code width} characters, at spaces where it can. */
    private static List<String> wrap(String text, int width) {
        List<String> parts = new ArrayList<>();
        String rest = text;
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

    private static String plain(java.math.BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
