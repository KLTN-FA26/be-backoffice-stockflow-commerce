package com.stockflow.invoicing.internal.service;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agreement Thông tư 91/2026/TT-BTC Điều 10 asks a seller and a business buyer to put in writing before an
 * invoice is adjusted or replaced: the bases in force since 1 July 2026 and none of the ones they replaced, the
 * invoice by its template, symbol, number and date, the reason, what the invoice says beside what is right, and both
 * parties' signature blocks.
 */
class InvoiceAdjustmentAgreementDocumentTest {

    private final InvoiceAdjustmentAgreementDocument document = new InvoiceAdjustmentAgreementDocument(
            new DocumentFonts(), DocumentSamples.seller());

    private static InvoiceAdjustmentAgreementDocument.Facts facts(boolean replacement, String contract) {
        return new InvoiceAdjustmentAgreementDocument.Facts("006/2026/BBTT-HĐ-APK-MLC", LocalDate.of(2026, 12, 20),
                replacement, contract, contract == null ? null : LocalDate.of(2026, 11, 9), DocumentSamples.BUYER,
                "1", "C26TAP", 42, LocalDate.of(2026, 12, 12), "M1-26-ABCDE-00000000042",
                "Ghi sai số lượng ly giấy 12oz so với số lượng thực giao theo Biên bản giao nhận ngày 12/12/2026",
                List.of(new InvoiceAdjustmentAgreementDocument.Change("Số lượng - Ly giấy 12oz in logo Mộc Lam",
                                "15.000 cái", "14.000 cái"),
                        new InvoiceAdjustmentAgreementDocument.Change("Thành tiền chưa thuế GTGT", "27.000.000 đồng",
                                "25.200.000 đồng"),
                        new InvoiceAdjustmentAgreementDocument.Change("Tiền thuế GTGT (8%)", "2.160.000 đồng",
                                "2.016.000 đồng"),
                        new InvoiceAdjustmentAgreementDocument.Change("Tổng cộng tiền thanh toán", "29.160.000 đồng",
                                "27.216.000 đồng (điều chỉnh giảm 1.944.000 đồng)")));
    }

    @Test
    @DisplayName("an adjustment agreement: the decree and circular in force, both parties, the invoice it concerns, "
            + "the reason, each item as recorded beside what is right, the undertaking, two signature blocks")
    void adjustment() throws IOException {
        byte[] pdf = DocumentSamples.keep("invoice-adjustment-agreement-sample",
                document.render(facts(false, "HD-20261109-000001-1")));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc",
                "BIÊN BẢN THỎA THUẬN ĐIỀU CHỈNH HÓA ĐƠN", "Số: 006/2026/BBTT-HĐ-APK-MLC",
                "Căn cứ Hợp đồng số HD-20261109-000001-1 ký ngày 09/11/2026;",
                "Căn cứ Nghị định số 254/2026/NĐ-CP ngày 30 tháng 6 năm 2026 của Chính phủ",
                "Căn cứ Thông tư số 91/2026/TT-BTC ngày 30 tháng 6 năm 2026 của Bộ trưởng Bộ Tài chính.",
                "Hôm nay, ngày 20 tháng 12 năm 2026",
                "BÊN BÁN (BÊN A): CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "Ông/Bà Trần Quốc An - Chức vụ: Giám đốc",
                "BÊN MUA (BÊN B): CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
                "Hai bên thống nhất lập biên bản ghi nhận nội dung sai của hóa đơn Mẫu số 1, ký hiệu C26TAP, số "
                        + "00000042, lập ngày 12 tháng 12 năm 2026, mã của cơ quan thuế M1-26-ABCDE-00000000042.",
                "Bên A sẽ lập hóa đơn điều chỉnh cho hóa đơn nêu trên.", "Lý do điều chỉnh:",
                "Hai bên thống nhất điều chỉnh như sau:", "Nội dung trước khi điều chỉnh", "Nội dung đúng",
                "15.000 cái", "14.000 cái", "29.160.000 đồng", "27.216.000 đồng",
                "Chúng tôi cam kết và hoàn toàn chịu trách nhiệm về việc điều chỉnh hóa đơn này.",
                "Bên A giữ 01 bản, Bên B giữ 01 bản.", "ĐẠI DIỆN BÊN A", "GIÁM ĐỐC", "(Ký, ghi rõ họ tên, đóng dấu)",
                "Trần Quốc An", "ĐẠI DIỆN BÊN B", "TỔNG GIÁM ĐỐC", "Lê Thị Mộc Lam");
        assertThat(text).as("never the decree and circulars that lapsed on 1 July 2026")
                .doesNotContain("123/2020", "70/2025", "32/2025", "78/2021", "?");
    }

    @Test
    @DisplayName("a replacement says replacement throughout, and a sale without a contract cites none")
    void replacementWithoutContract() throws IOException {
        String text = DocumentSamples.text(DocumentSamples.keep("invoice-replacement-agreement-sample",
                document.render(facts(true, null))));

        assertThat(text).contains("BIÊN BẢN THỎA THUẬN THAY THẾ HÓA ĐƠN",
                "Bên A sẽ lập hóa đơn thay thế cho hóa đơn nêu trên.", "Lý do thay thế:",
                "Chúng tôi cam kết và hoàn toàn chịu trách nhiệm về việc thay thế hóa đơn này.")
                .doesNotContain("Căn cứ Hợp đồng", "điều chỉnh hóa đơn này");
    }
}
