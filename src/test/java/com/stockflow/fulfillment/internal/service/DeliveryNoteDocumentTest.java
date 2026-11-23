package com.stockflow.fulfillment.internal.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import com.stockflow.fulfillment.api.ShippedCarton;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery note of a shipment (kltn-docs 09 §4.2; glossary "POD"), set as a record two companies sign: our company
 * and the shipment's code beside Quốc hiệu and Tiêu ngữ, the contract and order it is made under, both parties, the
 * cartons by SKU in a table with their LPNs, the vehicle, and three signature blocks - with dotted lines where a fact
 * is not known yet, so the same form prints before the vehicle is booked.
 */
class DeliveryNoteDocumentTest {

    private final DeliveryNoteDocument document = new DeliveryNoteDocument(new DocumentFonts(),
            DocumentSamples.seller());

    private static List<DeliveryNoteDocument.SkuCartons> goods() {
        return DeliveryNoteDocument.bySku(List.of(
                new ShippedCarton("LPN-000003", "LY16-ACME-02", "LOT-2", 40),
                new ShippedCarton("LPN-000001", "LY12-ACME-01", "LOT-1", 50),
                new ShippedCarton("LPN-000002", "LY12-ACME-01", "LOT-1", 50)),
                // LY16 has no name in the catalogue: printed by its code alone.
                Map.of("LY12-ACME-01", "Ly giấy 2 lớp in logo ACME - 12 oz"));
    }

    private static DeliveryNoteDocument.Facts facts(List<DeliveryNoteDocument.Vehicle> vehicles, Instant handedOver,
                                                    boolean redelivery) {
        return new DeliveryNoteDocument.Facts("SO-20261109-000001-S1", "SO-20261109-000001",
                "HD-20261109-000001-1", 2, redelivery, "Kho Hồ Chí Minh",
                "Lô C12, Đường số 3, Phường Tân Tạo, Thành phố Hồ Chí Minh", DocumentSamples.LONG_NAME,
                "Số 12 đường Nguyễn Huệ, phường Bến Nghé, quận 1, thành phố Hồ Chí Minh",
                "Trần Văn Bình - 0901234567", vehicles, handedOver, goods(), LocalDate.of(2026, 11, 15));
    }

    @Test
    @DisplayName("the cartons are grouped by SKU in code order, each with its catalogue name: how many, their pieces, "
            + "their LPNs in order")
    void cartonsBySku() {
        assertThat(goods()).containsExactly(
                new DeliveryNoteDocument.SkuCartons("LY12-ACME-01", "Ly giấy 2 lớp in logo ACME - 12 oz", 2, 100,
                        List.of("LPN-000001", "LPN-000002")),
                new DeliveryNoteDocument.SkuCartons("LY16-ACME-02", null, 1, 40, List.of("LPN-000003")));
    }

    @Test
    @DisplayName("after the handover it reads as a record of handover: head, bases, both parties, the goods table "
            + "with every LPN and the totals, the vehicle, the boxes to tick, three signature blocks - all inside the "
            + "margins of Nghị định 30")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = DocumentSamples.keep("delivery-note-sample", document.render(facts(List.of(
                new DeliveryNoteDocument.Vehicle("LD-20261115-0003", "Xe tải Nhanh", "51C-123.45", "Nguyễn Văn Tài",
                        "0912345678", "WB-778899")), Instant.parse("2026-11-15T02:05:00Z"), false)));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT",
                "BIÊN BẢN GIAO NHẬN HÀNG HÓA", "(Giao hàng đợt 2)",
                "Căn cứ Hợp đồng mua bán hàng hóa số HD-20261109-000001-1;",
                "Căn cứ Đơn đặt hàng số SO-20261109-000001.",
                "Hôm nay, ngày 15 tháng 11 năm 2026, tại Thành phố Hồ Chí Minh, chúng tôi gồm:",
                "BÊN GIAO (BÊN A):", "Kho xuất hàng", "Kho Hồ Chí Minh - Lô C12",
                "BÊN NHẬN (BÊN B):", "MIỀN NAM VIỆT NAM", "Người nhận hàng", "Trần Văn Bình - 0901234567",
                "Ly giấy 2 lớp in logo ACME - 12 oz", "Mã thùng (LPN): LPN-000001, LPN-000002", "LY16-ACME-02",
                "Tổng cộng", "3", "140",
                "Chuyến LD-20261115-0003 - Xe tải Nhanh: biển số 51C-123.45; tài xế Nguyễn Văn Tài;",
                "mã vận đơn WB-778899", "Thời điểm bàn giao: 09:05 ngày 15/11/2026",
                "Tình trạng hàng hóa khi nhận:", "Đủ số lượng, đúng quy cách, bao bì nguyên vẹn.", "Có sai khác:",
                "Biên bản được lập thành 02 bản", "ĐẠI DIỆN BÊN GIAO", "NGƯỜI VẬN CHUYỂN", "ĐẠI DIỆN BÊN NHẬN",
                "(Ký, ghi rõ họ tên)", "(POD)");
        assertThat(text).contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc",
                "Số: SO-20261109-000001-S1", "Thành phố Hồ Chí Minh, ngày 15 tháng 11 năm 2026", "Trang 1/")
                .as("a handover record states no price").doesNotContain("Đơn giá", "Thành tiền", "Giao lại")
                .as("every glyph is drawn").doesNotContain("?");
    }

    @Test
    @DisplayName("printed before the vehicle is booked: the day, the vehicle, the driver, the waybill and the moment "
            + "of the handover are lines to fill in; a redelivery says so")
    void beforeTheVehicle() throws IOException {
        String text = DocumentSamples.text(DocumentSamples.keep("delivery-note-before-dispatch",
                document.render(facts(List.of(), null, true))));
        assertThat(text).contains("Hôm nay, ngày ...... tháng ...... năm ............, tại địa điểm giao hàng",
                "Xe: biển số ..............................; tài xế ..............................",
                "Thời điểm bàn giao: ..............................", "Giao lại hàng đã hoàn về kho.");
    }

    @Test
    @DisplayName("the shipment's code is printed as a barcode the carrier and the warehouse scan (E20)")
    void codeIsScannable() throws Exception {
        byte[] pdf = document.render(facts(List.of(), null, false));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
            var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(page.getWidth(), page.getHeight(), pixels))));
            assertThat(decoded.getText()).isEqualTo("SO-20261109-000001-S1");
        }
    }
}
