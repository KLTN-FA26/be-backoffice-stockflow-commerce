package com.stockflow.design.internal.service;

import com.stockflow.design.internal.domain.BagSheet;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Whether a design's print file is the one the studio renders for its template (plan D2), checked when the design is
 * submitted - so a file the press could not use never reaches the coordinator - and again when it is approved, before
 * its boxes are stamped ({@link PrintReadyStamp}).
 *
 * <p>What the file holds: for a cup, one page per print area in the template's order; for a bag, one page, the flat
 * sheet the workshop die-cuts and folds ({@link BagSheet}; FE request 2026-11-18 BE-10). A bag's file of one page per
 * side - what the studio rendered before the sheet - is still taken, so a bag submitted before the change can be
 * approved: its pages are checked as a cup's are.</p>
 *
 * <p>Each page's <b>BleedBox</b> is the artwork's frame and must be that area's (or the sheet's) bleed box within half
 * a millimetre; the MediaBox may be larger - the studio leaves blank paper and a slug strip around the artwork - but
 * must contain it. A page that declares no BleedBox is measured by its MediaBox, which is what files rendered before
 * the studio declared boxes were measured by, so such a file still passes. (PDFBox's own fallback takes the CropBox
 * first: a file with a tight CropBox that passed before would then be refused.) A page that declares a TrimBox must
 * declare the trim the template computes, at its place inside the bleed box; a page without one gets it from the
 * stamp.</p>
 */
final class PrintFileGeometry {

    /** Points in a millimetre: PDF page sizes are in points. */
    static final float POINTS_PER_MM = 72f / 25.4f;
    /** How far a box may differ from the one it should be: half a millimetre, the studio's rounding and more. */
    private static final float TOLERANCE_PT = 0.5f * POINTS_PER_MM;

    private PrintFileGeometry() {
    }

    /**
     * What one page of the print file must be, in millimetres: its bleed box's size and the trim inside it, from the
     * bleed box's top-left as {@link PrintSurface} measures it. {@code what} names it in a refusal.
     */
    record PageFrame(String what, double flatWidthMm, double flatHeightMm, PrintSurface.Box trimMm) {
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
        int pages = document.getNumberOfPages();
        List<PageFrame> frames = frames(template, pages);
        if (frames == null) {
            return template.model() instanceof BagModel
                    ? "The print file has " + pages + " page(s); a bag's print file is one page, its flat sheet"
                    : "The print file has " + pages + " page(s); the template has " + template.printAreas().size()
                            + " print area(s)";
        }
        for (int i = 0; i < frames.size(); i++) {
            String problem = pageProblem(document.getPage(i), i + 1, frames.get(i));
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * The frame each page of a {@code pages}-page print file must have for {@code template}, or null when no file of
     * that many pages is the template's: a bag's one sheet, else one frame per print area, in order.
     */
    static List<PageFrame> frames(PrintConfig template, int pages) {
        if (template.model() instanceof BagModel bag && pages == 1 && template.printAreas().size() != 1) {
            BagSheet sheet = BagSheet.of(bag, template.printAreas());
            return List.of(new PageFrame("the bag's flat sheet", sheet.flatWidth(), sheet.flatHeight(),
                    sheet.trimBounds()));
        }
        if (pages != template.printAreas().size()) {
            return null;
        }
        return template.printAreas().stream().map(area -> frame(template, area)).toList();
    }

    private static PageFrame frame(PrintConfig template, PrintArea area) {
        PrintSurface surface = PrintSurface.of(template.model(), area);
        return new PageFrame("print area " + area.code(), surface.flatWidth(), surface.flatHeight(),
                surface.trimBounds());
    }

    /** The page's artwork frame: its BleedBox as declared (unclipped, so containment is checked), else its MediaBox. */
    static PDRectangle bleedBox(PDPage page) {
        PDRectangle declared = declared(page, COSName.BLEED_BOX);
        return declared != null ? declared : page.getMediaBox();
    }

    /** {@code box} as the page declares it, or null when it does not. */
    static PDRectangle declared(PDPage page, COSName box) {
        COSArray array = page.getCOSObject().getCOSArray(box);
        return array == null ? null : new PDRectangle(array);
    }

    /**
     * The TrimBox of a page whose bleed box is {@code bleed} and whose frame's trim is {@code trim}: PDF space grows
     * upwards from the bottom-left, the frame downwards from the bleed box's top-left, so the trim's top edge lies
     * {@code trim.y} below the bleed box's.
     */
    static PDRectangle trimBox(PDRectangle bleed, PrintSurface.Box trim) {
        return new PDRectangle(bleed.getLowerLeftX() + (float) trim.x() * POINTS_PER_MM,
                bleed.getUpperRightY() - (float) (trim.y() + trim.height()) * POINTS_PER_MM,
                (float) trim.width() * POINTS_PER_MM, (float) trim.height() * POINTS_PER_MM);
    }

    private static String pageProblem(PDPage page, int number, PageFrame frame) {
        PDRectangle bleed = bleedBox(page);
        if (!near(bleed.getWidth(), (float) frame.flatWidthMm() * POINTS_PER_MM)
                || !near(bleed.getHeight(), (float) frame.flatHeightMm() * POINTS_PER_MM)) {
            return "Page " + number + " is not the size of " + frame.what();
        }
        PDRectangle media = page.getMediaBox();
        if (bleed.getLowerLeftX() < media.getLowerLeftX() - TOLERANCE_PT
                || bleed.getLowerLeftY() < media.getLowerLeftY() - TOLERANCE_PT
                || bleed.getUpperRightX() > media.getUpperRightX() + TOLERANCE_PT
                || bleed.getUpperRightY() > media.getUpperRightY() + TOLERANCE_PT) {
            return "Page " + number + ": the MediaBox does not contain the BleedBox";
        }
        PDRectangle trim = declared(page, COSName.TRIM_BOX);
        if (trim != null) {
            PDRectangle expected = trimBox(bleed, frame.trimMm());
            if (!near(trim.getLowerLeftX(), expected.getLowerLeftX())
                    || !near(trim.getLowerLeftY(), expected.getLowerLeftY())
                    || !near(trim.getWidth(), expected.getWidth())
                    || !near(trim.getHeight(), expected.getHeight())) {
                return "Page " + number + ": the TrimBox is not the trim of " + frame.what();
            }
        }
        return null;
    }

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) <= TOLERANCE_PT;
    }
}
