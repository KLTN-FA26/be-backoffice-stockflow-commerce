package com.stockflow.order.internal.service;

import com.stockflow.common.label.LabelDrawing;
import com.stockflow.common.label.LabelFonts;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The A4 layout of the order module's documents - the sales contract (4B-4) and the production order slip (4A-3): lines
 * of text flowing down as many pages as they need, each wrapped at the page's width, in the Noto Sans the labels use,
 * which has the Vietnamese glyphs. One layout, so a change of margin or spacing reaches both documents.
 *
 * <p>A document that names a code a scanner reads - the production order's, the lot its goods are received under -
 * carries it at the top of its first page as a Code 128 barcode and a QR code, drawn by the label primitives
 * ({@link LabelDrawing}) so the scanners tuned on labels read it too.</p>
 */
final class PrintedDocument {

    enum Style { TITLE, HEADING, BOLD, TEXT }

    record Line(Style style, String text) {
        static Line title(String text) {
            return new Line(Style.TITLE, text);
        }

        static Line heading(String text) {
            return new Line(Style.HEADING, text);
        }

        static Line bold(String text) {
            return new Line(Style.BOLD, text);
        }

        static Line text(String text) {
            return new Line(Style.TEXT, text);
        }
    }

    private static final float MARGIN = 50;
    private static final float LINE = 14;
    /** The width the text runs in, in points: the A4 page less its margins. */
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final double PAGE_WIDTH_MM = LabelDrawing.millimetres(PDRectangle.A4.getWidth());
    private static final double PAGE_HEIGHT_MM = LabelDrawing.millimetres(PDRectangle.A4.getHeight());
    private static final double MARGIN_MM = LabelDrawing.millimetres(MARGIN);
    /** The code's band at the top of the first page: a QR code this wide and high, a barcode beside it. */
    private static final double CODE_BAND_MM = 20;
    private static final double BARCODE_WIDTH_MM = 70;
    private static final double BARCODE_HEIGHT_MM = 10;

    private PrintedDocument() {
    }

    /**
     * @param code drawn as a barcode and a QR code at the top of the first page, or null for none; [A-Z0-9-] only, as
     *             every label code is
     */
    static byte[] draw(LabelFonts fonts, List<Line> lines, String title, String code) {
        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
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
                    List<String> parts = wrap(font, size, fonts.drawable(line.text()));
                    float before = line.style() == Style.HEADING ? LINE / 2 : 0;
                    for (String part : parts) {
                        if (out == null || y - before < MARGIN) {
                            boolean first = out == null;
                            if (out != null) {
                                out.close();
                            }
                            PDPage page = new PDPage(PDRectangle.A4);
                            document.addPage(page);
                            out = new PDPageContentStream(document, page);
                            y = PDRectangle.A4.getHeight() - MARGIN;
                            if (first && code != null) {
                                code(out, code);
                                y -= LabelDrawing.points(CODE_BAND_MM) + LINE;
                            }
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
            throw new UncheckedIOException("Could not draw " + title, ex);
        }
    }

    /** The barcode at the top left, the QR code at the top right, inside the margins. */
    private static void code(PDPageContentStream out, String code) throws IOException {
        var page = new LabelDrawing.Cell(0, 0, PAGE_WIDTH_MM, PAGE_HEIGHT_MM, PAGE_HEIGHT_MM);
        LabelDrawing.barcode(out, page, code, MARGIN_MM, MARGIN_MM, BARCODE_WIDTH_MM, BARCODE_HEIGHT_MM);
        LabelDrawing.qr(out, page, code, PAGE_WIDTH_MM - MARGIN_MM - CODE_BAND_MM, MARGIN_MM, CODE_BAND_MM);
    }

    /**
     * {@code text} cut into lines that fit the page's width as drawn in {@code font} at {@code size} - measured, not
     * counted: a legal name in capitals is far wider than its letters suggest. Cut at spaces; a word wider than the page
     * is cut where it no longer fits.
     */
    private static List<String> wrap(PDFont font, float size, String text) throws IOException {
        List<String> parts = new ArrayList<>();
        String rest = text == null ? "" : text.strip();
        while (!rest.isEmpty() && width(font, size, rest) > WIDTH) {
            int cut = -1;
            for (int space = rest.indexOf(' '); space > 0; space = rest.indexOf(' ', space + 1)) {
                if (width(font, size, rest.substring(0, space)) > WIDTH) {
                    break;
                }
                cut = space;
            }
            if (cut <= 0) {
                cut = 1;
                while (cut < rest.length() && width(font, size, rest.substring(0, cut + 1)) <= WIDTH) {
                    cut++;
                }
            }
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).strip();
        }
        parts.add(rest);
        return parts;
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /** Whole units grouped with dots as Vietnamese documents write them: 1.234.567 VND. */
    static String money(BigDecimal amount, String currency) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        return format.format(amount == null ? BigDecimal.ZERO : amount) + " " + currency;
    }
}
