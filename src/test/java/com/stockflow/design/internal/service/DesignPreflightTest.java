package com.stockflow.design.internal.service;

import com.stockflow.common.storage.FileStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BR-DSG-001's machine checks against PDFs built for each case. */
class DesignPreflightTest {

    private final FileStorage storage = mock(FileStorage.class);
    private final DesignPreflight preflight = new DesignPreflight(storage);

    private DesignPreflight.Report inspect(byte[] bytes, String type) {
        when(storage.download("design-renders/x")).thenAnswer(call -> new ByteArrayInputStream(bytes));
        return preflight.inspect("design-renders/x", type);
    }

    private static byte[] pdf(Consumer<PDDocument> build) throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            build.accept(document);
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A page with {@code pixels x pixels} RGB image drawn {@code points} wide; bleed beyond the trim when asked. */
    private static byte[] withImage(int pixels, float points, boolean bleed) throws Exception {
        return pdf(document -> {
            try {
                var page = new PDPage(PDRectangle.A4);
                if (bleed) {
                    page.setTrimBox(new PDRectangle(9, 9, PDRectangle.A4.getWidth() - 18, PDRectangle.A4.getHeight() - 18));
                    page.setBleedBox(PDRectangle.A4);
                }
                document.addPage(page);
                var image = LosslessFactory.createFromImage(document,
                        new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB));
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, 50, 50, points, points);
                }
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void aBlankPagePassesWithOnlyTheBleedWarning() throws Exception {
        var report = inspect(pdf(document -> document.addPage(new PDPage(PDRectangle.A4))), "application/pdf");

        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).extracting(DesignPreflight.Finding::code).containsExactly("NO_BLEED");
    }

    @Test
    void aFontThatIsNotEmbeddedBlocks() throws Exception {
        byte[] bytes = pdf(document -> {
            try {
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Logo");
                    content.endText();
                }
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        var report = inspect(bytes, "application/pdf");

        assertThat(report.passed()).isFalse();
        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("FONT_NOT_EMBEDDED");
            assertThat(finding.detail()).contains("Helvetica");
            assertThat(finding.blocking()).isTrue();
        });
    }

    @Test
    void anImagePrintedBelow150DpiBlocksAndRgbIsAWarning() throws Exception {
        // 100 px over 288 pt (4 in): 25 dpi.
        var report = inspect(withImage(100, 288, true), "application/pdf");

        assertThat(report.passed()).isFalse();
        assertThat(report.findings()).extracting(DesignPreflight.Finding::code)
                .containsExactlyInAnyOrder("LOW_RESOLUTION", "RGB_COLOUR");
        assertThat(report.blockingSummary()).contains("LOW_RESOLUTION", "25 dpi");
    }

    @Test
    void aSharpImageWithBleedPassesWithTheColourWarning() throws Exception {
        // 1000 px over 144 pt (2 in): 500 dpi.
        var report = inspect(withImage(1000, 144, true), "application/pdf");

        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).extracting(DesignPreflight.Finding::code).containsExactly("RGB_COLOUR");
    }

    @Test
    void aFormDrawnInsideItselfIsReadNotRecursedForEver() throws Exception {
        byte[] bytes = pdf(document -> {
            try {
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
                form.setBBox(new PDRectangle(100, 100));
                var formResources = new org.apache.pdfbox.pdmodel.PDResources();
                var name = org.apache.pdfbox.cos.COSName.getPDFName("Fm0");
                formResources.put(name, form);
                form.setResources(formResources);
                try (var out = form.getContentStream().createOutputStream()) {
                    out.write("/Fm0 Do".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                var pageResources = new org.apache.pdfbox.pdmodel.PDResources();
                pageResources.put(name, form);
                page.setResources(pageResources);
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawForm(form);
                }
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        var report = inspect(bytes, "application/pdf");

        assertThat(report.passed()).isTrue();
    }

    @Test
    void aFileThatDoesNotOpenBlocks() {
        var report = inspect("%PDF-1.7 broken".getBytes(), "application/pdf");

        assertThat(report.passed()).isFalse();
        assertThat(report.findings()).extracting(DesignPreflight.Finding::code).containsExactly("PDF_UNREADABLE");
    }

    @Test
    void rasterArtworkIsReportedForTheReviewerToJudge() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1500, 600, BufferedImage.TYPE_INT_RGB), "png", png);

        var report = inspect(png.toByteArray(), "image/png");

        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("RASTER_ARTWORK");
            assertThat(finding.detail()).startsWith("1500 x 600 px");
            assertThat(finding.blocking()).isFalse();
        });
    }
}
