package com.stockflow.design.internal.service;

import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.PrintConfig;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Whether a design's print file is the one the studio renders for its template (plan D2): a PDF of one page per print
 * area, in the template's order, each the size of that area's bleed box. Checked when the design is submitted - so a
 * file the press could not use never reaches the Sales Manager - and again when it is approved, before its boxes are
 * stamped.
 */
final class PrintFileGeometry {

    /** Points in a millimetre: PDF page sizes are in points. */
    static final float POINTS_PER_MM = 72f / 25.4f;
    /** How far a page may differ from the bleed box it should be: half a millimetre, the studio's rounding and more. */
    private static final float TOLERANCE_PT = 0.5f * POINTS_PER_MM;

    private PrintFileGeometry() {
    }

    /**
     * Why {@code pdf} is not {@code template}'s print file, or null when it is. PDFBox reads lazily and fails on a
     * damaged file with more than an {@code IOException} - a runtime exception, or a stack overflow on a cyclic
     * structure, as {@code PdfContentPolicy} knows - and any of them means the file is not one the press can read.
     */
    static String problem(PrintFileCopy pdf, PrintConfig template) {
        try (PDDocument document = pdf.open()) {
            return problem(document, template);
        } catch (IOException | RuntimeException | StackOverflowError unreadable) {
            return "The print file is not a readable PDF";
        }
    }

    /** {@link #problem(PrintFileCopy, PrintConfig)} of a document already open. */
    static String problem(PDDocument document, PrintConfig template) {
        if (document.getNumberOfPages() != template.printAreas().size()) {
            return "The print file has " + document.getNumberOfPages() + " page(s); the template has "
                    + template.printAreas().size() + " print area(s)";
        }
        for (int i = 0; i < template.printAreas().size(); i++) {
            PDRectangle media = document.getPage(i).getMediaBox();
            PrintSurface surface = PrintSurface.of(template.model(), template.printAreas().get(i));
            if (Math.abs(media.getWidth() - (float) surface.flatWidth() * POINTS_PER_MM) > TOLERANCE_PT
                    || Math.abs(media.getHeight() - (float) surface.flatHeight() * POINTS_PER_MM) > TOLERANCE_PT) {
                return "Page " + (i + 1) + " is not the size of print area " + template.printAreas().get(i).code();
            }
        }
        return null;
    }
}
