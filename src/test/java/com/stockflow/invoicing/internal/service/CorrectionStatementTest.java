package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Correction;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sentence the law words for an adjustment and for a replacement (TT 91/2026/TT-BTC Điều 10 khoản 1 điểm b.1,
 * b.2; plans/E0-legal.md row 3). Compared whole: the words are the law's, so a change to the bundle that reads better
 * is still a change to what the invoice must say.
 */
class CorrectionStatementTest {

    /** Invoice 42 of series C26TSB, template 1, dated 5 November 2026 - a day and a month that need their zero. */
    private static Correction of(EInvoiceType type) {
        return new Correction(type, "1", "C26TSB", 42, LocalDate.of(2026, 11, 5), "sai so luong");
    }

    @Test
    @DisplayName("điểm b.1: an adjustment says 'Điều chỉnh cho hóa đơn Mẫu số... ký hiệu... số... ngày... tháng... năm'")
    void anAdjustmentNamesTheInvoiceItAdjusts() {
        assertThat(CorrectionStatement.of(of(EInvoiceType.ADJUSTMENT)))
                .isEqualTo("Điều chỉnh cho hóa đơn Mẫu số 1 ký hiệu C26TSB số 00000042 ngày 05 tháng 11 năm 2026");
    }

    @Test
    @DisplayName("điểm b.2: a replacement says 'Thay thế cho hóa đơn Mẫu số... ký hiệu... số... ngày... tháng... năm'")
    void aReplacementNamesTheInvoiceItReplaces() {
        assertThat(CorrectionStatement.of(of(EInvoiceType.REPLACEMENT)))
                .isEqualTo("Thay thế cho hóa đơn Mẫu số 1 ký hiệu C26TSB số 00000042 ngày 05 tháng 11 năm 2026");
    }

    @Test
    @DisplayName("the number is written with all its eight digits, and the day with both of its")
    void theNumberAndTheDateAreWrittenInFull() {
        assertThat(CorrectionStatement.of(new Correction(EInvoiceType.ADJUSTMENT, "1", "C26TSB", 99_999_999,
                LocalDate.of(2026, 12, 31), "sai don gia")))
                .isEqualTo("Điều chỉnh cho hóa đơn Mẫu số 1 ký hiệu C26TSB số 99999999 ngày 31 tháng 12 năm 2026");
    }

    @Test
    @DisplayName("an invoice that corrects nothing carries no such sentence")
    void anOriginalSaysNothing() {
        assertThat(CorrectionStatement.of(null)).isNull();
    }
}
