package com.stockflow.common.label;

import com.stockflow.common.label.LabelDrawing.Cell;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one label layout every printed code label uses: the code as large bold text at the top left, two smaller
 * lines under it, a QR code at the top right and a Code 128 barcode across the bottom, all three symbols carrying
 * exactly the code. Location labels (SCRUM-97) and carton labels (SCRUM-473) differ only in their code and two lines,
 * so they are laid out here once: a change of padding, spacing or squeeze reaches both, and one printer setup keeps
 * serving both.
 *
 * <p>Knows nothing about persistence: callers render after the read-only transaction that chose the labels has
 * ended, so no connection is held while a large PDF is drawn.</p>
 */
public final class CodeLabelSheet {

    private static final double TEXT_TO_QR_GAP_MM = 2;
    private static final double QR_TO_BARCODE_GAP_MM = 1.5;

    private static final float CODE_MAX_POINTS = 16;
    private static final float CODE_MIN_POINTS = 8;
    private static final float CODE_STEP_POINTS = 0.5f;
    private static final float LINE_SPACING = 1.25f;

    /**
     * One label. {@code code} is what the text and both symbols carry, [A-Z0-9-]; {@code firstLine} and
     * {@code secondLine} are free text, made drawable and cut to the text column here.
     */
    public record CodeLabel(String code, String firstLine, String secondLine) {
    }

    private CodeLabelSheet() {
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the given order
     * @throws IllegalArgumentException when there is no label; every caller's service refuses that case first
     */
    public static byte[] render(LabelFonts fonts, List<CodeLabel> labels, LabelFormat format) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("A label sheet needs at least one label");
        }
        try (PDDocument document = new PDDocument()) {
            Fonts loaded = new Fonts(
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true),
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true));
            PDRectangle pageSize = new PDRectangle(LabelDrawing.points(format.pageWidthMm()),
                    LabelDrawing.points(format.pageHeightMm()));
            // A line repeated on every label (a warehouse name) is cut once rather than 2,000 times.
            Map<String, String> fitted = new HashMap<>();
            int perPage = format.labelsPerPage();
            for (int first = 0; first < labels.size(); first += perPage) {
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    for (int slot = 0; slot < perPage && first + slot < labels.size(); slot++) {
                        drawLabel(out, format, cellOf(format, slot), labels.get(first + slot), fonts, loaded, fitted);
                    }
                }
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render labels", e);
        }
    }

    private static void drawLabel(PDPageContentStream out, LabelFormat format, Cell cell, CodeLabel label,
                                  LabelFonts fonts, Fonts loaded, Map<String, String> fitted) throws IOException {
        double qrSide = format.qrSideMm();
        double textWidth = format.labelWidthMm() - 2 * format.paddingMm() - qrSide - TEXT_TO_QR_GAP_MM;
        double innerLeft = cell.leftMm() + format.paddingMm();
        double innerTop = cell.topMm() + format.paddingMm();
        double innerWidth = cell.widthMm() - 2 * format.paddingMm();
        double innerHeight = cell.heightMm() - 2 * format.paddingMm();

        float codeSize = LabelDrawing.fittingSize(loaded.bold, label.code(), textWidth, CODE_MAX_POINTS,
                CODE_MIN_POINTS, CODE_STEP_POINTS);
        double baseline = innerTop + LabelDrawing.ascentMm(loaded.bold, codeSize);
        // Still too wide at the smallest size: squeeze the glyphs rather than cut the code or let it run over the
        // QR code, whose finder patterns it would break.
        float squeeze = (float) Math.min(1, textWidth / LabelDrawing.widthMm(loaded.bold, label.code(), codeSize));
        out.setHorizontalScaling(squeeze * 100);
        LabelDrawing.text(out, cell, loaded.bold, codeSize, label.code(), innerLeft, baseline);
        out.setHorizontalScaling(100);
        for (String line : List.of(label.firstLine(), label.secondLine())) {
            String drawn = fitted.get(line);
            if (drawn == null) {
                drawn = LabelDrawing.cut(loaded.regular, fonts.drawable(line), textWidth);
                fitted.put(line, drawn);
            }
            baseline += LabelDrawing.millimetres(LabelDrawing.CONTEXT_POINTS * LINE_SPACING);
            LabelDrawing.text(out, cell, loaded.regular, LabelDrawing.CONTEXT_POINTS, drawn, innerLeft, baseline);
        }

        LabelDrawing.qr(out, cell, label.code(), innerLeft + innerWidth - qrSide, innerTop, qrSide);

        double barTop = innerTop + qrSide + QR_TO_BARCODE_GAP_MM;
        LabelDrawing.barcode(out, cell, label.code(), innerLeft, barTop, innerWidth,
                innerTop + innerHeight - barTop);
    }

    private static Cell cellOf(LabelFormat format, int slot) {
        return new Cell((slot % format.columns()) * format.labelWidthMm(),
                format.topMarginMm() + (slot / format.columns()) * format.labelHeightMm(),
                format.labelWidthMm(), format.labelHeightMm(), format.pageHeightMm());
    }

    private record Fonts(PDFont bold, PDFont regular) {
    }
}
