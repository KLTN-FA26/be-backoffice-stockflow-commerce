package com.stockflow.procurement.internal.service;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The goods receipt voucher for returned goods in the Ministry of Finance's form (Mẫu số 01 - VT, Thông tư
 * 99/2025/TT-BTC): the unit and the form's number at the head, the columns lettered and numbered as the form letters
 * them, the quantity approved beside the quantity counted, the total in words only when it means something, and the
 * form's four signatures.
 */
class ReturnGoodsReceiptDocumentTest {

    private final ReturnGoodsReceiptDocument document = new ReturnGoodsReceiptDocument(new DocumentFonts(),
            DocumentSamples.seller());

    private static ReturnGoodsReceiptDocument.Facts facts(List<ReturnGoodsReceiptDocument.Line> lines) {
        return new ReturnGoodsReceiptDocument.Facts("PNK2612-0007", LocalDate.of(2026, 12, 18), "Nguyễn Văn Tài",
                "Biên bản thỏa thuận trả lại hàng hóa số 004/2026/BBTLH-APK-MLC ngày 15/12/2026",
                "CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM", "số 00000042 ngày 12/12/2026", "Kho hàng trả về",
                "Lô C12, Đường số 3, Phường Tân Tạo, Thành phố Hồ Chí Minh", lines,
                List.of("Biên bản thỏa thuận trả lại hàng hóa số 004/2026/BBTLH-APK-MLC",
                        "Yêu cầu trả hàng RMA-20261215-0004"), "Lê Minh Kho");
    }

    private static List<ReturnGoodsReceiptDocument.Line> costed() {
        return List.of(
                new ReturnGoodsReceiptDocument.Line("Ly giấy 12oz in logo Mộc Lam", "LY12-MLC-01", "Cái", 1_000, 800,
                        new BigDecimal("950"), "Thùng đã mở, đếm thực tế 800 cái; không nhập tồn, hủy theo phiếu"),
                new ReturnGoodsReceiptDocument.Line("Ly giấy 16oz hai lớp in logo Mộc Lam", "LY16-MLC-02", "Cái",
                        500, 500, new BigDecimal("1200"), null));
    }

    @Test
    @DisplayName("the voucher keeps the ministry's form: unit and Mẫu số 01 - VT, date and number, who delivered under "
            + "which document into which warehouse, columns A-D and 1-4, the row of totals, the total in words, four "
            + "signatures")
    void theForm() throws IOException {
        byte[] pdf = DocumentSamples.keep("return-goods-receipt-01vt-sample", document.render(facts(costed())));

        assertThat(DocumentSamples.pages(pdf)).isEqualTo(1);
        assertThat(DocumentSamples.within(pdf, 20, 15)).as("inside the margins").isTrue();
        String text = DocumentSamples.text(pdf);
        // 800 x 950 + 500 x 1.200 = 1.360.000
        assertThat(text).containsSubsequence("Đơn vị: CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "Mẫu số: 01 - VT",
                "(Kèm theo Thông tư số 99/2025/TT-BTC ngày 27 tháng 10 năm 2025 của Bộ trưởng Bộ Tài chính)",
                "PHIẾU NHẬP KHO", "Ngày 18 tháng 12 năm 2026", "Số: PNK2612-0007", "Nợ:", "Có:",
                "Họ và tên người giao: Nguyễn Văn Tài",
                "Theo Biên bản thỏa thuận trả lại hàng hóa số 004/2026/BBTLH-APK-MLC ngày 15/12/2026 của CÔNG TY CỔ "
                        + "PHẦN CÀ PHÊ MỘC LAM",
                "Hóa đơn bán hàng gốc: số 00000042 ngày 12/12/2026", "Nhập tại kho: Kho hàng trả về - địa điểm: Lô C12",
                "Mã số", "Đơn vị tính", "Đơn giá", "Thành tiền", "A", "B", "C", "D", "1", "2", "3", "4",
                "Ly giấy 12oz in logo Mộc Lam", "Thùng đã mở, đếm thực tế 800 cái", "LY12-MLC-01", "1.000", "800",
                "950", "760.000", "Cộng", "x", "1.360.000",
                "Tổng số tiền (viết bằng chữ): Một triệu ba trăm sáu mươi nghìn đồng.",
                "Số chứng từ gốc kèm theo:", "RMA-20261215-0004", "Ngày 18 tháng 12 năm 2026",
                "Người lập phiếu", "(Ký, họ tên)", "Lê Minh Kho", "Người giao hàng", "Nguyễn Văn Tài", "Thủ kho",
                "Kế toán trưởng", "(Hoặc bộ phận có nhu cầu nhập)");
        assertThat(text).as("the receipt voucher has no director's signature, unlike the issue voucher")
                .doesNotContain("Giám đốc", "GIÁM ĐỐC", "CỘNG HÒA", "?");
    }

    @Test
    @DisplayName("where the system holds no cost the price, the amount and the total are left for the accountant to "
            + "write in - never a total of the lines that happen to have one; a long return runs over pages")
    void withoutCostAndOverPages() throws IOException {
        List<ReturnGoodsReceiptDocument.Line> lines = new ArrayList<>(costed());
        for (int i = 0; i < 36; i++) {
            lines.add(new ReturnGoodsReceiptDocument.Line("Túi giấy kraft có quai in logo Mộc Lam - mẫu " + (i + 1),
                    "TUI-MLC-%02d".formatted(i + 1), "Cái", 200, 200, null, null));
        }
        byte[] pdf = DocumentSamples.keep("return-goods-receipt-01vt-uncosted", document.render(facts(lines)));

        int pages = DocumentSamples.pages(pdf);
        assertThat(pages).isGreaterThan(1);
        assertThat(DocumentSamples.within(pdf, 20, 15)).isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).contains("TUI-MLC-36", "Tổng số tiền (viết bằng chữ): ......", "Trang " + pages + "/" + pages)
                .doesNotContain("1.360.000", "Một triệu");
        assertThat(DocumentSamples.text(pdf, 2)).as("the table's header again").contains("Mã số", "Thành tiền");
    }
}
