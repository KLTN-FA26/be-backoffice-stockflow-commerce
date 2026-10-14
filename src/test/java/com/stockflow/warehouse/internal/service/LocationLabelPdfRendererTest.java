package com.stockflow.warehouse.internal.service;

import com.google.zxing.BarcodeFormat;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The PDF itself: page geometry per format, and - the point of the whole feature - that every label
 * scans back to its location code in both symbologies.
 */
class LocationLabelPdfRendererTest {

    private static final float DPI = 300;
    private static final LabelWords WORDS = new LabelWords("Kệ", "Tầng", "Bin");

    private final LocationLabelPdfRenderer renderer = new LocationLabelPdfRenderer(new LabelFonts());

    private static LocationLabel bin(String shelf, int level, String bin) {
        return new LocationLabel("HCM-" + shelf + "-" + level + "-" + bin, StorageLocationKind.BIN, shelf, level,
                bin, null, StorageClass.OVERSIZE);
    }

    private static LocationLabelSheet sheet(List<LocationLabel> labels) {
        return new LocationLabelSheet("HCM", "Kho Hồ Chí Minh", labels);
    }

    private static List<LocationLabel> bins(int count) {
        return IntStream.range(0, count).mapToObj(i -> bin("A" + (10 + i / 20), 1 + (i / 2) % 9, i % 2 == 0 ? "A" : "B"))
                .toList();
    }

    private PDDocument render(List<LocationLabel> labels, LabelFormat format) throws IOException {
        return Loader.loadPDF(renderer.render(sheet(labels), format, WORDS));
    }

    private static BufferedImage page(PDDocument doc, int index) throws IOException {
        return new PDFRenderer(doc).renderImageWithDPI(index, DPI, ImageType.RGB);
    }

    @Test
    void a4PutsTwentyFourLabelsOnAPage() throws IOException {
        try (PDDocument doc = render(bins(25), LabelFormat.A4_SHEET)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            PDRectangle box = doc.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(595.28f, within(0.5f));
            assertThat(box.getHeight()).isCloseTo(841.89f, within(0.5f));
        }
    }

    @Test
    void thermalPutsOneLabelOnAPage() throws IOException {
        try (PDDocument doc = render(bins(3), LabelFormat.THERMAL_ROLL)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            PDRectangle box = doc.getPage(2).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(170.08f, within(0.5f));
            assertThat(box.getHeight()).isCloseTo(113.39f, within(0.5f));
        }
    }

    @Test
    void everyLabelDecodesToItsCodeInBothSymbologies() throws IOException {
        List<LocationLabel> labels = List.of(bin("A01", 1, "A"), bin("A01", 1, "B"), bin("A01", 2, "A"),
                bin("B01", 12, "C07"), bin("B01", 2, "B"));
        try (PDDocument doc = render(labels, LabelFormat.A4_SHEET)) {
            BufferedImage page = page(doc, 0);
            for (int i = 0; i < labels.size(); i++) {
                BufferedImage cell = LabelDecoding.cell(page, LabelFormat.A4_SHEET, i, DPI);
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.CODE_128)).isEqualTo(labels.get(i).code());
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo(labels.get(i).code());
            }
        }
    }

    @Test
    void thermalLabelDecodesToo() throws IOException {
        LocationLabel label = bin("A01", 2, "B");
        try (PDDocument doc = render(List.of(label), LabelFormat.THERMAL_ROLL)) {
            BufferedImage cell = LabelDecoding.cell(page(doc, 0), LabelFormat.THERMAL_ROLL, 0, DPI);
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.CODE_128)).isEqualTo("HCM-A01-2-B");
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo("HCM-A01-2-B");
        }
    }

    @Test
    void vietnameseTextIsEmbeddedAndExtractable() throws IOException {
        try (PDDocument doc = render(List.of(bin("A01", 1, "A")), LabelFormat.A4_SHEET)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("HCM-A01-1-A", "Kho Hồ Chí Minh", "Kệ A01 · Tầng 1 · Bin A");
        }
    }

    @Test
    void anAreaLabelShowsTheAreaName() throws IOException {
        var area = new LocationLabel("HCM-RCV01", StorageLocationKind.AREA, null, null, null, "Khu nhận hàng",
                StorageClass.OVERSIZE);
        try (PDDocument doc = render(List.of(area), LabelFormat.THERMAL_ROLL)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("HCM-RCV01", "Khu nhận hàng").doesNotContain("Kệ");
        }
    }

    @Test
    void aCodeTooLongForCode128StillRendersAndItsQrDecodes() throws IOException {
        String code = "ABCDEFGHIJ-ABCDEFGHIJKLMNOPQRST-99-ABCDEFGHIJKLMNOPQRST";
        var label = new LocationLabel(code, StorageLocationKind.BIN, "ABCDEFGHIJKLMNOPQRST", 99,
                "ABCDEFGHIJKLMNOPQRST", null, StorageClass.NORMAL);
        try (PDDocument doc = Loader.loadPDF(renderer.render(new LocationLabelSheet("ABCDEFGHIJ", "Kho dài",
                List.of(label)), LabelFormat.A4_SHEET, WORDS))) {
            BufferedImage cell = LabelDecoding.cell(page(doc, 0), LabelFormat.A4_SHEET, 0, DPI);
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo(code);
        }
    }

    @Test
    void anEmptySheetIsRefused() {
        assertThatThrownBy(() -> renderer.render(sheet(List.of()), LabelFormat.A4_SHEET, WORDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameTheFontCannotDrawStillPrints() throws IOException {
        var area = new LocationLabel("HCM-RCV01", StorageLocationKind.AREA, null, null, null, "Khu	lạnh 🧊",
                StorageClass.COLD);
        try (PDDocument doc = Loader.loadPDF(renderer.render(new LocationLabelSheet("HCM", "Kho	🧊 lạnh",
                List.of(area)), LabelFormat.A4_SHEET, WORDS))) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Kho ? lạnh", "Khu lạnh ?");
            BufferedImage cell = LabelDecoding.cell(page(doc, 0), LabelFormat.A4_SHEET, 0, DPI);
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo("HCM-RCV01");
        }
    }

    /**
     * The limit's worst case: 2,000 labels with a 200-character warehouse name that must be cut on
     * every label. Coarse on purpose - it catches a return of the per-call font shaping cost, which
     * made this take 3.5-9 s, not machine-to-machine noise.
     */
    @Test
    void twoThousandLabelsRenderInAboutASecond() {
        var sheet = new LocationLabelSheet("HCM", "Kho Hồ Chí Minh ".repeat(13), bins(2000));
        renderer.render(sheet, LabelFormat.A4_SHEET, WORDS);   // warm-up: class loading, JIT
        long start = System.nanoTime();
        renderer.render(sheet, LabelFormat.A4_SHEET, WORDS);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }
}
