package com.stockflow.inventory.internal.service;

import com.google.zxing.BarcodeFormat;
import com.stockflow.common.label.LabelDecoding;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The carton label PDF: one page per label on a roll, and every label scans back to its LPN. */
class StockPackageLabelPdfRendererTest {

    private static final float DPI = 300;

    private final StockPackageLabelPdfRenderer renderer = new StockPackageLabelPdfRenderer(new LabelFonts());

    private static StockPackageLabel label(String lpn, int received) {
        return new StockPackageLabel(lpn, "CUP-12OZ", "Cốc giấy 12oz", received, 1000, "CARTON");
    }

    private PDDocument render(List<StockPackageLabel> labels, LabelFormat format) throws IOException {
        return Loader.loadPDF(renderer.render(labels, format));
    }

    @Test
    void aRollGetsOnePagePerLabelAndEachScansToItsLpnInBothSymbologies() throws IOException {
        List<StockPackageLabel> labels = List.of(label("LPN-20261026-000001", 1000), label("LPN-20261026-000002", 980),
                label("LPN-20261026-000003", 5));
        try (PDDocument doc = render(labels, LabelFormat.THERMAL_ROLL)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            for (int i = 0; i < labels.size(); i++) {
                BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(i, DPI, ImageType.RGB);
                BufferedImage cell = LabelDecoding.cell(page, LabelFormat.THERMAL_ROLL, 0, DPI);
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.CODE_128)).isEqualTo(labels.get(i).lpn());
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo(labels.get(i).lpn());
            }
        }
    }

    @Test
    void theLabelSaysTheSkuTheItemAndWhatWasCountedOutOfNominal() throws IOException {
        try (PDDocument doc = render(List.of(label("LPN-20261026-000002", 980)), LabelFormat.THERMAL_ROLL)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("LPN-20261026-000002", "CUP-12OZ", "Cốc giấy 12oz", "980 / 1000 CARTON");
        }
    }

    @Test
    void anA4SheetHoldsTwentyFourLabels() throws IOException {
        List<StockPackageLabel> labels = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> label("LPN-20261026-%06d".formatted(i + 1), 1000)).toList();
        try (PDDocument doc = render(labels, LabelFormat.A4_SHEET)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
            BufferedImage cell = LabelDecoding.cell(page, LabelFormat.A4_SHEET, 23, DPI);
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo("LPN-20261026-000024");
        }
    }

    @Test
    void anItemNameTheFontCannotDrawStillPrints() throws IOException {
        var odd = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", "Cốc\t🧊", null, 1000, "CARTON");
        try (PDDocument doc = render(List.of(odd), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Cốc ?", "- / 1000 CARTON");
        }
    }

    @Test
    void anEmptySheetIsRefused() {
        assertThatThrownBy(() -> renderer.render(List.of(), LabelFormat.A4_SHEET))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
