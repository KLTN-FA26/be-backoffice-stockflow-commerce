package com.stockflow.common.document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a business document writes a number, an amount and a date, in one place: a quotation and the contract it becomes
 * must not write the same total, or the same day, two ways.
 *
 * <p>Vietnamese papers group thousands with a dot and write dong without decimals ("133.100.000"); the unit goes in
 * the column heading, not after every figure. A date in running text is spelled "ngày 05 tháng 3 năm 2026": Nghị định
 * 30/2020/NĐ-CP pads a day below 10 and the months 1 and 2 with a zero, so a figure cannot be turned into another by
 * adding a digit in front of it.</p>
 */
public final class DocumentFormats {

    /** Every document is dated where the company is: a quote sent at 23:30 in Hà Nội is not dated the day before. */
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy")
            .withZone(VIETNAM);

    private DocumentFormats() {
    }

    /** A whole number grouped with dots: 1.234.567. */
    public static String number(long value) {
        return grouped().format(value);
    }

    /** An amount in whole dong grouped with dots, half a dong rounded up; no unit - the column or the label names it. */
    public static String money(BigDecimal amount) {
        return grouped().format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    /** A VAT rate as a tax invoice writes it: "8%", "10%"; a rate with a fraction keeps a decimal comma ("7,5%"). */
    public static String rate(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }

    /** 09/11/2026. */
    public static String date(LocalDate day) {
        return DAY.format(day);
    }

    /** "17:00 ngày 23/11/2026", in Vietnam's time. */
    public static String moment(Instant at) {
        return MOMENT.format(at);
    }

    /** "ngày 05 tháng 02 năm 2026", "ngày 23 tháng 11 năm 2026": the day always two digits, the month below 3 too. */
    public static String longDate(LocalDate day) {
        String month = day.getMonthValue() < 3 ? "0" + day.getMonthValue() : String.valueOf(day.getMonthValue());
        return "ngày %02d tháng %s năm %d".formatted(day.getDayOfMonth(), month, day.getYear());
    }

    private static DecimalFormat grouped() {
        // Not shared: DecimalFormat is not thread-safe, and documents are drawn on request threads.
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        return new DecimalFormat("#,##0", symbols);
    }
}
