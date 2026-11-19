package com.stockflow.design.internal.service;

import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The print file a design is submitted and approved with (plan D2; FE request 2026-11-18 BE-1, BE-10): a cup's file
 * one page per print area, a bag's one page of its flat sheet; each page measured by its BleedBox - the studio's page
 * is larger, with blank paper and a slug strip - and its TrimBox, where it declares one. A file from before the studio
 * declared boxes still passes, and a damaged file is a finding, never a server error, however PDFBox fails on it.
 */
class PrintFileGeometryTest {

    private static final float PT = 72f / 25.4f;

    private static PrintConfig template(ProductModel model, List<PrintArea> areas) {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SKU-1", "TPL", "Mẫu",
                300, "CMYK", List.of("image/png"), 20, null, null, true, model, areas, null, null, 1);
    }

    /** The 12 oz cup: the wall a sector, its bleed box 286.54 x 125.53 mm and its trim 279.67 x 120.85 mm. */
    static PrintConfig cup() {
        return template(new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white"), List.of(new PrintArea("BODY", "Thân ly",
                0, 0, 279.67, 120.85, 3, 2, new PrintAreaShape.AnnularSector(230.04, 327.05, 0.84894, 8), "body")));
    }

    /** The request's bag: W 260, g 120, H 320, T 40, G 20, B 72, every side's bleed 3 mm. */
    static PrintConfig bag() {
        return template(new BagModel(260, 120, 320, 40, 20, 72, "twisted", "kraft-brown"), List.of(
                new PrintArea("FRONT", "Mặt trước", 0, 0, 260, 280, 5, 3, new PrintAreaShape.Rect(), "front"),
                new PrintArea("BACK", "Mặt sau", 0, 0, 260, 280, 5, 3, new PrintAreaShape.Rect(), "back"),
                new PrintArea("LEFT", "Hông trái", 0, 0, 120, 280, 5, 3, new PrintAreaShape.Rect(), "left"),
                new PrintArea("RIGHT", "Hông phải", 0, 0, 120, 280, 5, 3, new PrintAreaShape.Rect(), "right")));
    }

    /** A box in millimetres: lower-left x and y, width, height. */
    record Mm(double x, double y, double width, double height) {
        PDRectangle pt() {
            return new PDRectangle((float) x * PT, (float) y * PT, (float) width * PT, (float) height * PT);
        }
    }

    /** A page as the studio's export declares it: {@code media}, and {@code bleed} and {@code trim} when not null. */
    record Page(Mm media, Mm bleed, Mm trim) {
        static Page plain(double widthMm, double heightMm) {
            return new Page(new Mm(0, 0, widthMm, heightMm), null, null);
        }

        /**
         * The storefront's page since PR #51: BleedBox {@code [0, 0, W, H]}, 5 mm of blank paper left, right and top,
         * the 14 mm slug strip below, and the TrimBox around the cut path from the bleed box's origin.
         */
        static Page studio(double widthMm, double heightMm, PrintSurface.Box trim) {
            return new Page(new Mm(-5, -14, widthMm + 10, heightMm + 19), new Mm(0, 0, widthMm, heightMm),
                    new Mm(trim.x(), heightMm - trim.y() - trim.height(), trim.width(), trim.height()));
        }
    }

    static byte[] pdf(Page... pages) throws Exception {
        try (var document = new PDDocument()) {
            for (Page page : pages) {
                PDPage pdfPage = new PDPage(page.media().pt());
                if (page.bleed() != null) {
                    pdfPage.setBleedBox(page.bleed().pt());
                }
                if (page.trim() != null) {
                    pdfPage.setTrimBox(page.trim().pt());
                }
                document.addPage(pdfPage);
            }
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String problemOf(byte[] bytes, PrintConfig template) throws Exception {
        try (PrintFileCopy file = PrintFileCopy.empty()) {
            Files.write(file.path(), bytes);
            return PrintFileGeometry.problem(file, template);
        }
    }

    private static PrintSurface wall() {
        PrintConfig cup = cup();
        return PrintSurface.of(cup.model(), cup.printAreas().get(0));
    }

    @Test
    void aStudioPageIsMeasuredByItsBleedBoxNotItsLargerMediaBox() throws Exception {
        PrintSurface wall = wall();
        assertThat(wall.flatWidth()).isCloseTo(286.54, org.assertj.core.api.Assertions.within(0.01));
        assertThat(wall.flatHeight()).isCloseTo(125.53, org.assertj.core.api.Assertions.within(0.01));

        assertThat(problemOf(pdf(Page.studio(wall.flatWidth(), wall.flatHeight(), wall.trimBounds())), cup()))
                .isNull();
        // The request's own figures: BleedBox 286.54 x 125.53, TrimBox 279.67 x 120.85, MediaBox 296.54 x 144.53.
        assertThat(problemOf(pdf(new Page(new Mm(-5, -14, 296.54, 144.53), new Mm(0, 0, 286.54, 125.53),
                new Mm(wall.trimBounds().x(), 125.53 - wall.trimBounds().y() - 120.85, 279.67, 120.85))), cup()))
                .isNull();
    }

    @Test
    void aBleedBoxOfTheWrongSizeIsRefusedWhateverTheMediaBox() throws Exception {
        PrintSurface wall = wall();

        assertThat(problemOf(pdf(Page.studio(wall.flatWidth() + 1, wall.flatHeight(), wall.trimBounds())), cup()))
                .isEqualTo("Page 1 is not the size of print area BODY");
        // Within half a millimetre is the studio's rounding.
        assertThat(problemOf(pdf(Page.studio(wall.flatWidth() + 0.4, wall.flatHeight() - 0.4, wall.trimBounds())),
                cup())).isNull();
    }

    @Test
    void aBleedBoxReachingPastTheMediaBoxIsRefused() throws Exception {
        PrintSurface wall = wall();
        Page studio = Page.studio(wall.flatWidth(), wall.flatHeight(), wall.trimBounds());
        Page cropped = new Page(new Mm(0, 0, wall.flatWidth() - 10, wall.flatHeight()), studio.bleed(), null);

        assertThat(problemOf(pdf(cropped), cup())).isEqualTo("Page 1: the MediaBox does not contain the BleedBox");
    }

    @Test
    void aDeclaredTrimBoxMustBeTheTemplatesTrimInItsPlace() throws Exception {
        PrintSurface wall = wall();
        PrintSurface.Box trim = wall.trimBounds();
        PrintSurface.Box moved = new PrintSurface.Box(trim.x() + 2, trim.y(), trim.width(), trim.height());
        PrintSurface.Box shrunk = new PrintSurface.Box(trim.x(), trim.y(), trim.width() - 2, trim.height());
        PrintSurface.Box nudged = new PrintSurface.Box(trim.x() + 0.3, trim.y() - 0.3, trim.width(), trim.height());

        assertThat(problemOf(pdf(Page.studio(wall.flatWidth(), wall.flatHeight(), moved)), cup()))
                .isEqualTo("Page 1: the TrimBox is not the trim of print area BODY");
        assertThat(problemOf(pdf(Page.studio(wall.flatWidth(), wall.flatHeight(), shrunk)), cup()))
                .isEqualTo("Page 1: the TrimBox is not the trim of print area BODY");
        assertThat(problemOf(pdf(Page.studio(wall.flatWidth(), wall.flatHeight(), nudged)), cup())).isNull();
    }

    @Test
    void aFileFromBeforeTheStudioDeclaredBoxesStillPasses() throws Exception {
        PrintSurface wall = wall();
        // No BleedBox, no TrimBox: measured by the MediaBox, as it was when it was submitted.
        assertThat(problemOf(pdf(Page.plain(wall.flatWidth(), wall.flatHeight())), cup())).isNull();

        // A tight CropBox does not stand in for the BleedBox it never declared: the file passed before and still does.
        byte[] cropped;
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle((float) wall.flatWidth() * PT, (float) wall.flatHeight() * PT));
            page.setCropBox(new PDRectangle(3 * PT, 3 * PT, 200 * PT, 100 * PT));
            document.addPage(page);
            var out = new ByteArrayOutputStream();
            document.save(out);
            cropped = out.toByteArray();
        }
        assertThat(problemOf(cropped, cup())).isNull();
    }

    @Test
    void aCupsFileIsOnePagePerPrintArea() throws Exception {
        PrintSurface wall = wall();
        Page page = Page.studio(wall.flatWidth(), wall.flatHeight(), wall.trimBounds());

        assertThat(problemOf(pdf(page, page), cup())).isEqualTo("The print file has 2 page(s); the template has 1 "
                + "print area(s)");
    }

    @Test
    void aBagsFileIsOnePageItsFlatSheet() throws Exception {
        Page sheet = Page.studio(786, 398, new PrintSurface.Box(3, 3, 780, 392));

        assertThat(problemOf(pdf(sheet), bag())).isNull();
        assertThat(problemOf(pdf(Page.plain(786, 398)), bag())).isNull();
        assertThat(problemOf(pdf(Page.studio(266, 286, new PrintSurface.Box(3, 3, 260, 280))), bag()))
                .isEqualTo("Page 1 is not the size of the bag's flat sheet");
        // The trim the request computes is 780 x 392 at (b, b); the sheet's faces alone are not it.
        assertThat(problemOf(pdf(Page.studio(786, 398, new PrintSurface.Box(23, 43, 740, 280))), bag()))
                .isEqualTo("Page 1: the TrimBox is not the trim of the bag's flat sheet");
        assertThat(problemOf(pdf(sheet, sheet), bag()))
                .isEqualTo("The print file has 2 page(s); a bag's print file is one page, its flat sheet");
    }

    @Test
    void aBagFileOfOnePagePerSideFromBeforeTheSheetIsStillTaken() throws Exception {
        Page wide = Page.plain(266, 286);
        Page narrow = Page.plain(126, 286);

        assertThat(problemOf(pdf(wide, wide, narrow, narrow), bag())).isNull();
        assertThat(problemOf(pdf(wide, narrow, narrow, narrow), bag()))
                .isEqualTo("Page 2 is not the size of print area BACK");
    }

    @Test
    void aDamagedFileIsAFindingNotAnError() throws Exception {
        assertThat(problemOf("not a pdf".getBytes(), bag())).isEqualTo("The print file is not a readable PDF");
        // The page tree claims pages it does not have: PDFBox fails on reaching one with a runtime exception.
        byte[] lying;
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(266 * PT, 286 * PT)));
            document.getPages().getCOSObject().setInt(COSName.COUNT, 4);
            var out = new ByteArrayOutputStream();
            document.save(out);
            lying = out.toByteArray();
        }
        assertThat(problemOf(lying, bag())).isEqualTo("The print file is not a readable PDF");
    }

    @Test
    void theTemporaryFileIsGoneOnceClosed() throws Exception {
        java.nio.file.Path path;
        try (PrintFileCopy file = PrintFileCopy.empty()) {
            path = file.path();
            assertThat(path).exists();
        }
        assertThat(path).doesNotExist();
    }
}
