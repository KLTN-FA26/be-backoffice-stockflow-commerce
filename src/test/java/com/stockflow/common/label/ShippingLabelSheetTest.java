package com.stockflow.common.label;

import com.google.zxing.BarcodeFormat;
import com.stockflow.common.label.ShippingLabelSheet.ShippingLabel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipping label of a carton (team plan 4C-6; kltn-docs 08 §4.3 step 6): one 100 x 150 mm page per carton, what a
 * scanner reads back from it, and a label that still prints for any text a customer typed.
 */
class ShippingLabelSheetTest {

    private static final float DPI = 200f;
    private final LabelFonts fonts = new LabelFonts();

    private static ShippingLabel label(String lpn, String address, boolean rotatable) {
        return new ShippingLabel("SO-20261113-0001", lpn, "SO-20261113-0001-C1", 1, "SOFA-3S-GREY", 1,
                new BigDecimal("52.500"), new BigDecimal("306.375"), new BigDecimal("215"), new BigDecimal("95"),
                new BigDecimal("90"), rotatable, "Nguyen Van An", "0900000001", address, "Kho TP.HCM",
                "1 Đường Số 7, Quận 7, TP.HCM");
    }

    private static ShippingLabel label(String lpn) {
        return label(lpn, "12 Lê Lợi, Phường Bến Nghé, Quận 1, TP.HCM, VN", true);
    }

    private static String text(byte[] pdf, int page) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    private static BufferedImage render(byte[] pdf, int page) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFRenderer(document).renderImageWithDPI(page, DPI);
        }
    }

    @Test
    @DisplayName("one label is one page of 100 x 150 mm; N labels are N pages in the order given")
    void aPagePerLabel() throws IOException {
        byte[] one = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000001")));
        assertThat(new String(one, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(one)) {
            assertThat(document.getNumberOfPages()).isOne();
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(100 / 25.4f * 72, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(document.getPage(0).getMediaBox().getHeight()).isCloseTo(150 / 25.4f * 72, org.assertj.core.data.Offset.offset(0.5f));
        }

        List<ShippingLabel> labels = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            labels.add(label("LPN-20261113-00000" + i));
        }
        byte[] many = ShippingLabelSheet.render(fonts, labels);
        try (PDDocument document = Loader.loadPDF(many)) {
            assertThat(document.getNumberOfPages()).isEqualTo(7);
        }
        for (int i = 1; i <= 7; i++) {
            assertThat(text(many, i)).contains("LPN-20261113-00000" + i);
        }
    }

    @Test
    @DisplayName("no label is nothing to print: IllegalArgumentException (the service refuses first)")
    void noLabels() {
        assertThatThrownBy(() -> ShippingLabelSheet.render(fonts, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the QR code and the Code 128 barcode both read back as the LPN")
    void theSymbolsCarryTheLpn() throws IOException {
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000042")));
        BufferedImage page = render(pdf, 0);
        assertThat(LabelDecoding.decode(page, BarcodeFormat.QR_CODE)).isEqualTo("LPN-20261113-000042");
        assertThat(LabelDecoding.decode(page, BarcodeFormat.CODE_128)).isEqualTo("LPN-20261113-000042");
    }

    @Test
    @DisplayName("the label carries the order, label code and version, recipient, sender, SKU, count, weights and size")
    void theWordsOnTheLabel() throws IOException {
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000001")));
        String text = text(pdf, 1);
        assertThat(text).contains("SO-20261113-0001").contains("SO-20261113-0001-C1").contains("label v1")
                .contains("Nguyen Van An").contains("0900000001").contains("SKU  SOFA-3S-GREY").contains("QTY  1")
                .contains("52.5 kg").contains("306.375 kg").contains("215 x 95 x 90 cm").contains("FROM / RETURN TO");
        assertThat(text).as("a rotatable carton has no warning").doesNotContain("DO NOT ROTATE");
    }

    @Test
    @DisplayName("a carton that must not be turned says so in a box")
    void aNonRotatableCarton() throws IOException {
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000001", "1 Le Loi", false)));
        assertThat(text(pdf, 1)).contains("DO NOT ROTATE");
    }

    @Test
    @DisplayName("Vietnamese names and addresses are drawn with their diacritics")
    void vietnameseText() throws IOException {
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000001")));
        assertThat(text(pdf, 1)).contains("Lê Lợi").contains("Bến Nghé").contains("Đường Số 7");
    }

    @Test
    @DisplayName("a very long address wraps into at most four lines, cut with an ellipsis, and the page still has its barcode")
    void aLongAddress() throws IOException {
        String longAddress = ("Số 1234567 đường Nguyễn Thị Minh Khai nối dài, khu phố 12, phường Tân Hưng Thuận, "
                + "thành phố Hồ Chí Minh, gần ngã tư giao với đường tỉnh lộ 15, cạnh kho hàng màu xanh, "
                + "gọi cho anh Tâm trước khi giao, không giao giờ nghỉ trưa, ").repeat(6);
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(label("LPN-20261113-000007", longAddress, true)));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).as("never spills onto a second page").isOne();
        }
        assertThat(text(pdf, 1)).contains("…");
        assertThat(LabelDecoding.decode(render(pdf, 0), BarcodeFormat.CODE_128)).isEqualTo("LPN-20261113-000007");
    }

    @Test
    @DisplayName("a character neither font has (emoji, CJK) is replaced, never thrown at mid-render; blank and null texts print")
    void undrawableCharacters() throws IOException {
        ShippingLabel odd = new ShippingLabel("SO-1", "LPN-20261113-000009", "SO-1-C1", 3, null, 1, null, null, null, null,
                null, true, "Khách 😀 田中", null, "Địa chỉ \u0007 🚀", null, null);
        byte[] pdf = ShippingLabelSheet.render(fonts, List.of(odd));
        String text = text(pdf, 1);
        assertThat(text).contains("Khách").contains("?").contains("label v3").doesNotContain("😀");
        assertThat(LabelDecoding.decode(render(pdf, 0), BarcodeFormat.QR_CODE)).isEqualTo("LPN-20261113-000009");
    }
}
