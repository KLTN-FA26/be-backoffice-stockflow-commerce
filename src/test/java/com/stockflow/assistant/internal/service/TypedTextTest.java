package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §5.5: a phone number, an e-mail address or a tax code typed into a note does not reach the model provider;
 * the codes and figures the answer needs do, exactly as typed.
 */
class TypedTextTest {

    @Test
    @DisplayName("phone numbers in every usual shape are masked, and the text around them is kept")
    void phones() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Chờ anh Hùng (0903 123 456) duyệt file in", "Chờ anh Hùng ([phone]) duyệt file in");
        cases.put("CUSTOMER_REQUEST: gọi lại 0909.888.777", "CUSTOMER_REQUEST: gọi lại [phone]");
        cases.put("SĐT:0912345678 gấp", "SĐT:[phone] gấp");
        cases.put("liên hệ 0903-123-456", "liên hệ [phone]");
        cases.put("+84 903 123 456", "[phone]");
        cases.put("(+84) 903-123-456", "[phone]");
        cases.put("+84903123456", "[phone]");
        cases.put("bàn giao tại (028) 3822 1234", "bàn giao tại [phone]");
        cases.put("(0236) 382 1234", "[phone]");
        cases.put("+84 28 3822 1234", "[phone]");
        cases.put("5.000.000 0903123456", "5.000.000 [phone]");
        cases.put("SO-20261028-000123 0903123456", "SO-20261028-000123 [phone]");
        cases.put("Emoji 😀 0903123456", "Emoji 😀 [phone]");
        cases.forEach((typed, masked) -> assertThat(TypedText.masked(typed)).as(typed).isEqualTo(masked));
    }

    @Test
    @DisplayName("e-mail addresses are masked whole, even when they hold a phone number")
    void emails() {
        assertThat(TypedText.masked("Chờ anh Hùng (0903 123 456, hung@abc.vn) duyệt"))
                .isEqualTo("Chờ anh Hùng ([phone], [e-mail]) duyệt");
        assertThat(TypedText.masked("gửi a.b+in@sub.cong-ty.com.vn nhé")).isEqualTo("gửi [e-mail] nhé");
        assertThat(TypedText.masked("0903123456@gmail.com hỏi")).isEqualTo("[e-mail] hỏi");
    }

    @Test
    @DisplayName("a tax code is masked after its label, however the label is typed, in every shape a tax code has")
    void labelledTaxCodes() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("MST: 0312345678", "MST: [tax code]");
        cases.put("Mã số thuế 0312345678-001", "Mã số thuế [tax code]");
        cases.put("ma so thue 031 234 5678", "ma so thue [tax code]");
        cases.put("MÃ SỐ THUẾ: 0312345678", "MÃ SỐ THUẾ: [tax code]");
        cases.put("hộ kinh doanh, MST 079123456789", "hộ kinh doanh, MST [tax code]");
        cases.put("MST0312345678.", "MST[tax code].");
        cases.forEach((typed, masked) -> assertThat(TypedText.masked(typed)).as(typed).isEqualTo(masked));
    }

    @Test
    @DisplayName("a mobile number glued to its label, joined by a dash or written internationally is masked too")
    void gluedAndInternationalMobiles() {
        for (String typed : List.of("SĐT0903123456", "sdt0903123456", "Zalo0903123456", "LH-0903123456",
                "anh Hùng-0903123456", "84903123456", "84 903 123 456", "0084903123456", "0084 903 123 456",
                "+84 (0) 903 123 456", "0903 - 123 - 456", "0903  123  456")) {
            assertThat(TypedText.masked(typed)).as(typed).contains("[phone]").doesNotContain("123");
        }
        assertThat(TypedText.masked("KH: Cty ABC, sđt0903123456")).isEqualTo("KH: Cty ABC, sđt[phone]");
    }

    @Test
    @DisplayName("a tax code a few words after its label is masked")
    void taxCodeAfterAFewWords() {
        assertThat(TypedText.masked("MST của khách: 1012345678")).isEqualTo("MST của khách: [tax code]");
        assertThat(TypedText.masked("MST hộ kinh doanh 079123456789")).isEqualTo("MST hộ kinh doanh [tax code]");
    }

    @Test
    @DisplayName("order numbers, codes, amounts, dates, quantities and longer numbers are left as typed")
    void leavesBusinessDataAlone() {
        for (String typed : List.of(
                "Nhặt thiếu 5 nắp EVAL-LID-90 tại HCM-A01-1-B, chờ kiểm đếm lại",
                "Đơn SO-20991231-900001 giá 1250000000 VND",
                "Chuyển khoản 12.500.000đ ngày 28/10/2026 lúc 08:30",
                "125.000.000.000 VND",
                "TK 0071001234567 Vietcombank",
                "Lô LOT-0012345678 bị lỗi",
                "giao 30 thùng, còn 0 thùng",
                "INVENTORY: chờ 2026-10-30")) {
            assertThat(TypedText.masked(typed)).as(typed).isEqualTo(typed);
        }
        assertThat(TypedText.masked(null)).isNull();
        assertThat(TypedText.masked("")).isEmpty();
    }
}
