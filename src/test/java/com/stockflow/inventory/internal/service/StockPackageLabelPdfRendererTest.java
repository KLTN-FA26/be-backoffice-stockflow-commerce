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

/** The carton label PDF: one page per label on a roll, every label scans back to its LPN, and says what is inside. */
class StockPackageLabelPdfRendererTest {

    private static final float DPI = 300;
    /** The note's Vietnamese half, read from the bundle the label reads. */
    private static final String NOT_COUNTED_VI = java.util.ResourceBundle.getBundle("i18n.messages",
            java.util.Locale.forLanguageTag("vi")).getString("label.carton.not-counted");

    private final StockPackageLabelPdfRenderer renderer = new StockPackageLabelPdfRenderer(new LabelFonts());

    private static StockPackageLabel label(String lpn, int units) {
        return new StockPackageLabel(lpn, "CUP-12OZ", "Paper cup 12oz", "PRD-20261111-0001", true, units, "CARTON");
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
    void theLabelSaysTheSkuNameLotAndWhatWasCountedOutOfNominal() throws IOException {
        try (PDDocument doc = render(List.of(label("LPN-20261026-000002", 980)), LabelFormat.THERMAL_ROLL)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("LPN-20261026-000002", "CUP-12OZ", "980 / 980 CARTON", "Paper cup 12oz",
                    "PRD-20261111-0001", "SKU", "ITEM", "LOT");
        }
    }

    @Test
    void aLongNameTakesTwoLinesAndTheRestIsCutAndTheLabelStillScans() throws IOException {
        String name = "Paper cup 500ml printed both sides for a tea shop with a very long name that goes on and on and on";
        var long_ = new StockPackageLabel("LPN-20261026-000001", "CUP12-0312345678-01", name, "PRD-20261111-0001",
                true, 50, "CARTON");
        for (LabelFormat format : LabelFormat.values()) {
            try (PDDocument doc = render(List.of(long_), format)) {
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).contains("Paper cup 500ml", "…").doesNotContain("and on and on and on");
                BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
                BufferedImage cell = LabelDecoding.cell(page, format, 0, DPI);
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.CODE_128)).isEqualTo("LPN-20261026-000001");
                assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo("LPN-20261026-000001");
            }
        }
    }

    @Test
    void aLabelWithoutNameOrLotLeavesThoseFieldsOut() throws IOException {
        var bare = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", null, null, false, 1000, "CARTON");
        try (PDDocument doc = render(List.of(bare), LabelFormat.THERMAL_ROLL)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("CUP-12OZ", "- / 1000 CARTON").doesNotContain("ITEM", "LOT");
        }
    }

    @Test
    void theNotCountedNoteIsBilingualWhenItFitsAndVietnameseAloneWhenNot() throws IOException {
        var small = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", null, null, false, 50, "CARTON");
        try (PDDocument doc = render(List.of(small), LabelFormat.A4_SHEET)) {
            assertThat(new PDFTextStripper().getText(doc)).contains(NOT_COUNTED_VI + " / NOT COUNTED");
        }
        var wide = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", null, null, false, 1000, "CARTON");
        try (PDDocument doc = render(List.of(wide), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).contains(NOT_COUNTED_VI)
                    .doesNotContain("NOT COUNTED", "…");
        }
    }

    @Test
    void aBlankLotIsLeftOutLikeAMissingOne() throws IOException {
        var blank = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", null, "  ", true, 50, "CARTON");
        try (PDDocument doc = render(List.of(blank), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("CUP-12OZ").doesNotContain("LOT");
        }
    }

    @Test
    void aDecomposedVietnameseNameIsComposedBeforeItIsDrawn() throws IOException {
        String composed = "Ly giấy in logo";
        String decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo(composed);
        var label = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", decomposed, null, true, 50, "CARTON");
        try (PDDocument doc = render(List.of(label), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).contains(composed);
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
    void aBadgeLeavingNoRoomDropsTheNoteRatherThanRunIntoTheQrCode() throws IOException {
        var wide = new StockPackageLabel("LPN-20261026-000001", "CUP-12OZ", null, null, false, 1000000,
                "CTN-24X500ML-PRT"); // the longest unit code (16) and a seven-digit count fill the header

        try (PDDocument doc = render(List.of(wide), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).doesNotContain(NOT_COUNTED_VI.substring(0, 1) + "…");
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
            BufferedImage cell = LabelDecoding.cell(page, LabelFormat.THERMAL_ROLL, 0, DPI);
            assertThat(LabelDecoding.decode(cell, BarcodeFormat.QR_CODE)).isEqualTo("LPN-20261026-000001");
        }
    }

    @Test
    void aSkuTheFontCannotDrawStillPrints() throws IOException {
        var odd = new StockPackageLabel("LPN-20261026-000001", "CUP	🧊", "Cup 🧊", null, false, 1000, "CARTON");
        try (PDDocument doc = render(List.of(odd), LabelFormat.THERMAL_ROLL)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("CUP ?", "Cup ?", "- / 1000 CARTON");
        }
    }

    @Test
    void anEmptySheetIsRefused() {
        assertThatThrownBy(() -> renderer.render(List.of(), LabelFormat.A4_SHEET))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
