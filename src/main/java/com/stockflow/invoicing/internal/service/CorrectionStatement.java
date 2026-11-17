package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Correction;
import com.stockflow.invoicing.internal.domain.EInvoiceType;

import java.time.LocalDate;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The sentence an adjustment or a replacement must carry (TT 91/2026/TT-BTC Điều 10 khoản 1 điểm b.1, b.2): "Điều chỉnh
 * cho hóa đơn Mẫu số... ký hiệu... số... ngày... tháng... năm", "Thay thế cho hóa đơn Mẫu số... ký hiệu... số...
 * ngày... tháng... năm". The law gives the words, so they are written in one place and every rendering - the sandbox's
 * XML and PDF - says the same.
 */
final class CorrectionStatement {

    private CorrectionStatement() {
    }

    /** The sentence, in Vietnamese as the invoice is; null for an invoice that corrects nothing. */
    static String of(Correction correction) {
        if (correction == null) {
            return null;
        }
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        LocalDate date = correction.issueDate();
        return t.getString(correction.type() == EInvoiceType.REPLACEMENT ? "einvoice.correction.replaces"
                : "einvoice.correction.adjusts").formatted(correction.templateCode(), correction.symbol(),
                correction.invoiceNumber(), "%02d".formatted(date.getDayOfMonth()),
                "%02d".formatted(date.getMonthValue()), Integer.toString(date.getYear()));
    }
}
