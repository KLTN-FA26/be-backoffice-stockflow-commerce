package com.stockflow.order.internal.service;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two papers that follow a signed contract: its appendix (Bộ luật Dân sự 2015 Điều 403) and the record that
 * liquidates it (Điều 422). Both are set as the contract is - the nation's head, the bases, both parties, numbered
 * clauses, two signature blocks headed by the signatories' titles - and both state their figures in tables whose
 * totals the paper works out itself.
 */
class ContractFollowUpDocumentsTest {

    private static final String CONTRACT = "HD-20261109-000001-1";

    private final ContractAppendixDocument appendix = new ContractAppendixDocument(new DocumentFonts(),
            DocumentSamples.seller());
    private final ContractLiquidationDocument liquidation = new ContractLiquidationDocument(new DocumentFonts(),
            DocumentSamples.seller());

    private static ContractAppendixDocument.Facts appendixFacts(boolean goods, boolean draft) {
        return new ContractAppendixDocument.Facts(1, "PL01-" + CONTRACT, CONTRACT, LocalDate.of(2026, 11, 9),
                SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER, LocalDate.of(2026, 11, 23),
                "Bên B đề nghị tăng số lượng ly 12oz và lùi ngày giao đợt 2",
                List.of(new ContractAppendixDocument.Change("Số lượng ly giấy 12oz (Điều 1)", "20.000 cái",
                                "25.000 cái"),
                        new ContractAppendixDocument.Change("Thời gian giao hàng đợt 2 (Điều 4)",
                                "Từ 01/12/2026 đến 03/12/2026", "Từ 08/12/2026 đến 10/12/2026"),
                        new ContractAppendixDocument.Change("Tổng giá trị Hợp đồng (Điều 1)", "48.600.000 đồng",
                                "58.320.000 đồng")),
                goods ? List.of(new ContractAppendixDocument.GoodsLine("LY12-MLC-01", "Ly giấy 12oz in logo Mộc Lam",
                                "Cái", 25_000, new BigDecimal("1800"), new BigDecimal("45000000"),
                                new BigDecimal("8")),
                        new ContractAppendixDocument.GoodsLine("LY16-MLC-02",
                                "Ly giấy 16oz hai lớp in logo Mộc Lam", "Cái", 4_000, new BigDecimal("2250"),
                                new BigDecimal("9000000"), new BigDecimal("8"))) : List.of(),
                goods ? new SalesContracts.Totals(new BigDecimal("54000000"), new BigDecimal("4320000"),
                        new BigDecimal("58320000"), "VND") : null, draft);
    }

    private static ContractLiquidationDocument.Facts liquidationFacts(boolean delivered) {
        List<ContractLiquidationDocument.GoodsLine> goods = List.of(
                new ContractLiquidationDocument.GoodsLine("LY12-MLC-01", "Ly giấy 12oz in logo Mộc Lam", "Cái",
                        25_000, delivered ? 25_000 : 0, delivered ? 1_000 : 0, new BigDecimal("1800")),
                new ContractLiquidationDocument.GoodsLine("LY16-MLC-02", "Ly giấy 16oz hai lớp in logo Mộc Lam",
                        "Cái", 4_000, delivered ? 4_000 : 0, 0, new BigDecimal("2250")));
        ContractLiquidationDocument.Payment deposit = new ContractLiquidationDocument.Payment(
                LocalDate.of(2026, 11, 10), "FT26314000123", new BigDecimal("17496000"));
        return new ContractLiquidationDocument.Facts("TL-" + CONTRACT, CONTRACT, LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 12, 28), SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER,
                delivered ? null : "lô hàng giao không thành công và Bên B không còn nhu cầu nhận hàng", goods,
                new BigDecimal("58320000"), delivered ? new BigDecimal("58320000") : BigDecimal.ZERO,
                delivered ? new BigDecimal("1944000") : BigDecimal.ZERO,
                delivered ? List.of(deposit, new ContractLiquidationDocument.Payment(LocalDate.of(2026, 12, 12),
                        "FT26346000456", new BigDecimal("38000000"))) : List.of(deposit),
                BigDecimal.ZERO, delivered ? List.of("số 00000042 (12/12/2026)", "số 00000057 (20/12/2026, điều "
                        + "chỉnh giảm)") : List.of(), delivered ? LocalDate.of(2027, 1, 10) : null);
    }

    @Test
    @DisplayName("an appendix reads as one: its number and the contract it belongs to, the bases, both parties, each "
            + "change beside what the contract said, the goods and value as amended, the closing clause, both titles")
    void appendixInFull() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-appendix-sample", appendix.render(appendixFacts(true, false)));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "PHỤ LỤC HỢP ĐỒNG SỐ 01",
                "Số: PL01-" + CONTRACT, "(Kèm theo Hợp đồng mua bán hàng hóa số " + CONTRACT + " ngày 09/11/2026)",
                "Căn cứ Bộ luật Dân sự số 91/2015/QH13",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + CONTRACT + " ký ngày 09/11/2026 giữa hai Bên;",
                "Hôm nay, ngày 23 tháng 11 năm 2026, tại Thành phố Hồ Chí Minh, chúng tôi gồm:",
                "BÊN BÁN (BÊN A): CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "BÊN MUA (BÊN B): CÔNG TY CỔ PHẦN CÀ PHÊ",
                "ĐIỀU 1. NỘI DUNG SỬA ĐỔI, BỔ SUNG", "1.1.", "Lý do sửa đổi, bổ sung:", "1.2.",
                "Theo Hợp đồng", "Sửa đổi, bổ sung thành", "20.000 cái", "25.000 cái",
                "ĐIỀU 2. HÀNG HÓA VÀ GIÁ TRỊ HỢP ĐỒNG SAU ĐIỀU CHỈNH", "Ly giấy 12oz in logo Mộc Lam", "25.000",
                "45.000.000", "54.000.000", "4.320.000", "58.320.000",
                "Năm mươi tám triệu ba trăm hai mươi nghìn đồng",
                "ĐIỀU 3. ĐIỀU KHOẢN CHUNG", "3.1.", "là một phần không thể tách rời của Hợp đồng số " + CONTRACT,
                "3.4.", "ĐẠI DIỆN BÊN A", "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An", "ĐẠI DIỆN BÊN B",
                "Lê Thị Mộc Lam");
        assertThat(text).as("each signatory's title heads their block").contains("GIÁM ĐỐC", "TỔNG GIÁM ĐỐC")
                .doesNotContain("?");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isZero();
    }

    @Test
    @DisplayName("an appendix that moves neither goods nor money has no goods clause and leaves no gap in the "
            + "numbers; a copy for review is marked a draft on every page")
    void appendixWithoutGoods() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-appendix-draft", appendix.render(appendixFacts(false, true)));

        assertThat(DocumentSamples.text(pdf)).contains("ĐIỀU 1. NỘI DUNG SỬA ĐỔI, BỔ SUNG",
                "ĐIỀU 2. ĐIỀU KHOẢN CHUNG").doesNotContain("ĐIỀU 3", "HÀNG HÓA VÀ GIÁ TRỊ");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isEqualTo(DocumentSamples.pages(pdf));
    }

    @Test
    @DisplayName("a liquidation settles the contract in tables: contracted, delivered and returned line by line, then "
            + "from the contract's value down to the balance it works out itself, in figures and in words")
    void liquidationInFull() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-liquidation-sample", liquidation.render(liquidationFacts(true)));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        // 58.320.000 delivered - 1.944.000 returned - 17.496.000 - 38.000.000 paid = 880.000 still owed.
        assertThat(text).containsSubsequence("BIÊN BẢN THANH LÝ HỢP ĐỒNG MUA BÁN HÀNG HÓA", "Số: TL-" + CONTRACT,
                "Căn cứ Bộ luật Dân sự số 91/2015/QH13", "Căn cứ Luật Thương mại số 36/2005/QH11",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + CONTRACT + " ký ngày 09/11/2026 giữa hai Bên;",
                "Hôm nay, ngày 28 tháng 12 năm 2026",
                "ĐIỀU 1. THỎA THUẬN CHẤM DỨT HỢP ĐỒNG", "đã được thực hiện xong",
                "ĐIỀU 2. NGHĨA VỤ ĐÃ HOÀN THÀNH", "SL theo hợp đồng", "SL thực giao", "SL trả lại", "SL quyết toán",
                "Ly giấy 12oz in logo Mộc Lam", "25.000", "25.000", "1.000", "24.000", "1.800", "43.200.000",
                "Giá trị Hợp đồng", "58.320.000", "Giảm trừ do trả lại hàng, điều chỉnh hóa đơn", "-1.944.000",
                "Bên B đã thanh toán ngày 10/11/2026", "FT26314000123", "-17.496.000",
                "Bên B còn phải thanh toán", "880.000", "Tám trăm tám mươi nghìn đồng",
                "số 00000042 (12/12/2026)",
                "ĐIỀU 3. NGHĨA VỤ CÒN LẠI CỦA CÁC BÊN", "880.000 đồng trước ngày 10/01/2027",
                "ĐIỀU 4. ĐIỀU KHOẢN CHUNG", "Ngoài các nghĩa vụ nêu tại Điều 3",
                "ĐẠI DIỆN BÊN A", "ĐẠI DIỆN BÊN B");
        assertThat(text).contains("GIÁM ĐỐC", "TỔNG GIÁM ĐỐC").doesNotContain("?");
    }

    @Test
    @DisplayName("a contract that ends with nothing delivered is liquidated too (Q-6): the reason, no goods table, "
            + "and what the seller still pays back")
    void liquidationWithNothingDelivered() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-liquidation-undelivered",
                liquidation.render(liquidationFacts(false)));

        String text = DocumentSamples.text(pdf);
        assertThat(text).contains("Do lô hàng giao không thành công và Bên B không còn nhu cầu nhận hàng, các Bên",
                "Bên A chưa giao hàng hóa nào cho Bên B theo Hợp đồng.", "Bên A còn phải hoàn trả", "17.496.000",
                "Mười bảy triệu bốn trăm chín mươi sáu nghìn đồng",
                "Bên A hoàn trả cho Bên B số tiền là 17.496.000 đồng trước ngày ....../....../............")
                .doesNotContain("SL thực giao", "bảo hành");
    }
}
