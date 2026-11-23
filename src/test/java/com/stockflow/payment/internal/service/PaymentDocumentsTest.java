package com.stockflow.payment.internal.service;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.DocumentSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three papers about money between the company and a customer: the confirmation of a payment received, the
 * confirmation of a refund - both for bank transfers, so neither is the ministry's cash voucher - and the record that
 * reconciles the customer's debt, whose balances the paper works out from its own rows.
 */
class PaymentDocumentsTest {

    private static final String CONTRACT = "HD-20261109-000001-1";

    private final DocumentFonts fonts = new DocumentFonts();

    @Test
    @DisplayName("a payment confirmation: the company's head, who paid what under which contract, the amount in "
            + "figures and words, the bank's reference, where the contract now stands - and that it is no cash receipt")
    void paymentConfirmation() throws IOException {
        byte[] pdf = DocumentSamples.keep("payment-confirmation-sample",
                new PaymentConfirmationDocument(fonts, DocumentSamples.seller()).render(
                        new PaymentConfirmationDocument.Facts("XNTT2611-0015", LocalDate.of(2026, 11, 23),
                                DocumentSamples.BUYER, CONTRACT, "SO-20261109-000001",
                                "Đợt 2/3 - Thanh toán trước khi giao hàng", new BigDecimal("25000000"),
                                LocalDate.of(2026, 11, 23),
                                Instant.parse("2026-11-23T03:20:00Z"), "FT26327000789", new BigDecimal("58320000"),
                                new BigDecimal("42496000"), "Phạm Thu Hà")));

        assertThat(DocumentSamples.pages(pdf)).isEqualTo(1);
        assertThat(DocumentSamples.within(pdf, 20, 15)).as("inside the margins").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "Số: XNTT2611-0015",
                "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026", "PHIẾU XÁC NHẬN THANH TOÁN",
                "Kính gửi: CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM", "0398765432",
                "xác nhận đã nhận khoản thanh toán của Quý Công ty như sau:",
                "Đợt 2/3 - Thanh toán trước khi giao hàng theo Hợp đồng số " + CONTRACT,
                "25.000.000 đồng", "Hai mươi lăm triệu đồng.",
                "Chuyển khoản vào tài khoản số 0071001234567 tại Ngân hàng TMCP Ngoại thương Việt Nam",
                "FT26327000789", "Ngày nhận tiền (theo sao kê)", "23/11/2026", "10:20 ngày 23/11/2026",
                "Giá trị Hợp đồng", "Còn phải thanh toán", "58.320.000", "42.496.000", "15.824.000",
                "không phải là phiếu thu tiền mặt", "NGƯỜI LẬP PHIẾU", "(Ký, họ tên)", "Phạm Thu Hà",
                "ĐẠI DIỆN CÔNG TY", "GIÁM ĐỐC", "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An");
        assertThat(text).as("not the cash voucher of Thông tư 99").doesNotContain("Mẫu số", "01 - TT", "?");
    }

    @Test
    @DisplayName("a refund confirmation names what the refund follows from - the return, the adjusting invoice, the "
            + "contract - the amount both ways, the customer's account, and three who sign; before the transfer the "
            + "reference and the moment are lines to fill in")
    void refundConfirmation() throws IOException {
        RefundConfirmationDocument document = new RefundConfirmationDocument(fonts, DocumentSamples.seller());
        byte[] pdf = DocumentSamples.keep("refund-confirmation-sample", document.render(refund(
                LocalDate.of(2026, 12, 22), "FT26356000321")));

        assertThat(DocumentSamples.pages(pdf)).isEqualTo(1);
        assertThat(DocumentSamples.within(pdf, 20, 15)).as("inside the margins").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("PHIẾU HOÀN TIỀN", "Kính gửi: CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
                "xác nhận hoàn trả cho Quý Công ty khoản tiền như sau:",
                "Hoàn tiền hàng bán bị trả lại do lỗi in", "RMA-20261215-0004", "00000057 (20/12/2026)",
                "Hợp đồng số " + CONTRACT + "; đơn hàng số SO-20261109-000001",
                "1.944.000 đồng", "Một triệu chín trăm bốn mươi bốn nghìn đồng.", "Chuyển khoản",
                "0441000765432 tại Ngân hàng TMCP Á Châu (ACB)", "FT26356000321", "Ngày chuyển tiền", "22/12/2026",
                "không phải là phiếu chi tiền mặt", "NGƯỜI LẬP PHIẾU", "KẾ TOÁN TRƯỞNG", "ĐẠI DIỆN CÔNG TY",
                "GIÁM ĐỐC", "Trần Quốc An");
        assertThat(text).doesNotContain("Mẫu số", "02 - TT", "?");

        String before = DocumentSamples.text(DocumentSamples.keep("refund-confirmation-before-transfer",
                document.render(refund(null, null))));
        assertThat(before).containsSubsequence("Số tham chiếu giao dịch", "..............................",
                "Ngày chuyển tiền", "..............................");
    }

    private static RefundConfirmationDocument.Facts refund(LocalDate transferredOn, String reference) {
        return new RefundConfirmationDocument.Facts("PHT2612-0004", LocalDate.of(2026, 12, 22),
                DocumentSamples.BUYER, "Hoàn tiền hàng bán bị trả lại do lỗi in", "RMA-20261215-0004",
                "00000057 (20/12/2026)", CONTRACT, "SO-20261109-000001", new BigDecimal("1944000"),
                "0441000765432 tại Ngân hàng TMCP Á Châu (ACB) - chủ tài khoản: CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
                transferredOn, reference, "Phạm Thu Hà");
    }

    private static DebtReconciliationDocument.Entry entry(int day, String reference, String description,
                                                          long debit, long credit) {
        return new DebtReconciliationDocument.Entry(LocalDate.of(2026, 12, day), reference, description,
                BigDecimal.valueOf(debit), BigDecimal.valueOf(credit));
    }

    @Test
    @DisplayName("a debt reconciliation is a ledger two companies sign: each document of the period with the balance "
            + "after it, the period's totals, and a closing balance and conclusion worked out from the rows")
    void debtReconciliation() throws IOException {
        byte[] pdf = DocumentSamples.keep("debt-reconciliation-sample",
                new DebtReconciliationDocument(fonts, DocumentSamples.seller()).render(
                        new DebtReconciliationDocument.Facts("011/2026/BBĐCCN-APK-MLC", LocalDate.of(2026, 12, 31),
                                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), CONTRACT,
                                DocumentSamples.BUYER, new BigDecimal("5000000"), List.of(
                                entry(12, "HĐ 00000042", "Hóa đơn giao hàng đợt 1", 34_992_000, 0),
                                entry(12, "FT26346000456", "Thanh toán đợt 2", 0, 38_000_000),
                                entry(18, "HĐ 00000051", "Hóa đơn giao hàng đợt 2", 23_328_000, 0),
                                entry(20, "HĐ 00000057", "Hóa đơn điều chỉnh giảm do trả lại hàng", 0, 1_944_000),
                                entry(22, "PHT2612-0004", "Hoàn tiền hàng trả lại", 1_944_000, 0)),
                                new BigDecimal("2000000"))));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        // 5.000.000 + 34.992.000 - 38.000.000 + 23.328.000 - 1.944.000 + 1.944.000 = 25.320.000
        assertThat(text).containsSubsequence("BIÊN BẢN ĐỐI CHIẾU CÔNG NỢ", "(Từ ngày 01/12/2026 đến ngày 31/12/2026)",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + CONTRACT + ";", "Căn cứ vào biên bản giao nhận hàng hóa;",
                "Hôm nay, ngày 31 tháng 12 năm 2026",
                "BÊN BÁN (BÊN A): CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "Tài khoản số",
                "BÊN MUA (BÊN B): CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
                "Cùng nhau xác nhận - đối chiếu công nợ tính từ ngày 01/12/2026 đến ngày 31/12/2026",
                "Số dư đầu kỳ", "5.000.000", "12/12/2026", "Hóa đơn giao hàng đợt 1", "34.992.000", "39.992.000",
                "Thanh toán đợt 2", "38.000.000", "1.992.000", "25.320.000",
                "Cộng phát sinh", "60.264.000", "39.944.000", "Số dư cuối kỳ", "25.320.000",
                "Kết luận:", "Tính đến ngày 31/12/2026, Bên B còn phải thanh toán cho Bên A số tiền là 25.320.000 đồng.",
                "Hai mươi lăm triệu ba trăm hai mươi nghìn đồng", "Trong đó nợ quá hạn thanh toán: 2.000.000 đồng.",
                "Mỗi bên giữ 01 bản làm cơ sở cho việc thanh toán sau này giữa hai bên.",
                "ĐẠI DIỆN BÊN A", "GIÁM ĐỐC", "Trần Quốc An", "ĐẠI DIỆN BÊN B", "TỔNG GIÁM ĐỐC", "Lê Thị Mộc Lam");
        assertThat(text).contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Số: 011/2026/BBĐCCN-APK-MLC")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("a long ledger runs over pages with its header on each; a balance in the customer's favour is said "
            + "as what the seller owes")
    void longLedgerAndCreditBalance() throws IOException {
        List<DebtReconciliationDocument.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            entries.add(entry(i, "FT2634%07d".formatted(i), "Thanh toán theo sao kê ngân hàng", 0, 1_000_000));
        }
        byte[] pdf = DocumentSamples.keep("debt-reconciliation-long",
                new DebtReconciliationDocument(fonts, DocumentSamples.seller()).render(
                        new DebtReconciliationDocument.Facts("012/2026/BBĐCCN-APK-MLC", LocalDate.of(2026, 12, 31),
                                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), null,
                                new DocumentParts.Party(DocumentSamples.LONG_NAME, "0312345679", "Số 12 đường "
                                        + "Nguyễn Huệ, Phường Sài Gòn, Thành phố Hồ Chí Minh", null, null),
                                new BigDecimal("28500000"), entries, null)));

        int pages = DocumentSamples.pages(pdf);
        assertThat(pages).isGreaterThan(1);
        assertThat(DocumentSamples.within(pdf, 30, 15)).isTrue();
        assertThat(DocumentSamples.text(pdf, 2)).as("the table's header again").contains("Diễn giải", "Số dư");
        assertThat(DocumentSamples.text(pdf)).contains("Tính đến ngày 31/12/2026, Bên A còn phải hoàn trả cho Bên B "
                + "số tiền là 1.500.000 đồng.", "Trang " + pages + "/" + pages)
                .doesNotContain("Căn cứ Hợp đồng", "nợ quá hạn");
    }
}
