package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime number check (design §7): the golden scorer's definition of an ungrounded number, run on every answer,
 * reading amounts and rounding the way Vietnamese answers write them.
 */
class AnswerNumbersTest {

    @Test
    @DisplayName("a number in the answer that no source holds is unverified; small numbers, years and dates are not "
            + "checked")
    void ungrounded() {
        List<String> sources = List.of("{\"onHand\":40,\"expiryDate\":\"2026-11-04\",\"revenue\":12500000}");

        assertThat(AnswerNumbers.unverified("1. Còn 40 cái, hết hạn 04/11/2026; doanh thu 12.500.000 ₫.", sources))
                .isEmpty();
        assertThat(AnswerNumbers.unverified("Còn khoảng 45 cái", sources)).containsExactly("45");
    }

    @Test
    @DisplayName("what the prompt lets the model work out inside one record is grounded; arithmetic across records is "
            + "not")
    void workedOut() {
        List<String> sources = List.of("{\"lines\":[{\"sku\":\"CUP-12OZ\",\"ordered\":5000,\"shipped\":3000,"
                + "\"closed\":500},{\"sku\":\"LID-90\",\"ordered\":800,\"shipped\":0,\"closed\":0}],\"total\":14}");

        assertThat(AnswerNumbers.unverified("Còn 1.500 cốc sắp giao", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Còn 12 PO chưa liệt kê", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Tổng cộng 5.800 cái", sources)).containsExactly("5.800");
    }

    @Test
    @DisplayName("an amount in triệu, tỷ or nghìn is read as the amount: 35 triệu is 35.000.000, 12,5 triệu "
            + "12.500.000, and returned as written")
    void abbreviatedAmounts() {
        List<String> sources = List.of("{\"revenue\":35000000,\"previous\":{\"revenue\":12500000},"
                + "\"averageOrderValue\":2340000,\"fee\":1200000000}");

        assertThat(AnswerNumbers.unverified("Doanh thu 35 triệu, kỳ trước 12,5 triệu; giá trị đơn trung bình "
                + "2,34 triệu; phí 1,2 tỷ.", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Doanh thu 35tr, kỳ trước 12.500k.", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Doanh thu 128,4 triệu, tăng mạnh.", sources))
                .containsExactly("128,4 triệu");
        assertThat(AnswerNumbers.unverified("Doanh thu 5 triệu.", sources))
                .as("an amount in millions is never a small number").containsExactly("5 triệu");
    }

    @Test
    @DisplayName("a figure counts as what it rounds to at the precision written: 125 triệu for 124.800.000 and 11,9% "
            + "or 12% for 11.94 are grounded, 128 triệu and 13% are not")
    void rounding() {
        List<String> sources = List.of("{\"revenue\":124800000,\"revenueChangePercent\":11.94}");

        assertThat(AnswerNumbers.unverified("Doanh thu khoảng 125 triệu, tăng 11,9%.", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Doanh thu 124,8 triệu, tăng 12%.", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Doanh thu 128 triệu, tăng 13%.", sources))
                .containsExactly("128 triệu", "13%");
        assertThat(AnswerNumbers.unverified("Doanh thu 124.800.001 ₫.", sources))
                .as("a whole amount written in full is exact").containsExactly("124.800.001");
    }

    @Test
    @DisplayName("a result's numbers are read as the values they are: 12.5 grounds 12,5 and never 125; written 12,5 "
            + "in text, it is not 125 either")
    void exactSourceValues() {
        assertThat(AnswerNumbers.unverified("Tăng 125%.", List.of("{\"revenueChangePercent\":12.5}")))
                .containsExactly("125%");
        assertThat(AnswerNumbers.unverified("Tăng 12,5%.", List.of("{\"revenueChangePercent\":12.5}"))).isEmpty();
        assertThat(AnswerNumbers.unverified("Có 125 cái.", List.of("{\"note\":\"tăng 12,5 so với trước\"}")))
                .containsExactly("125");
        assertThat(AnswerNumbers.readings("12,5")).containsExactly(new AnswerNumbers.Reading(new BigDecimal("12.5"), 1));
        assertThat(AnswerNumbers.readings("1.276")).containsExactly(new AnswerNumbers.Reading(new BigDecimal("1276"), 0),
                new AnswerNumbers.Reading(new BigDecimal("1.276"), 3));
        assertThat(AnswerNumbers.readings("1.250,5"))
                .containsExactly(new AnswerNumbers.Reading(new BigDecimal("1250.5"), 1));
        assertThat(AnswerNumbers.readings("12.500.000"))
                .containsExactly(new AnswerNumbers.Reading(new BigDecimal("12500000"), 0));
    }

    @Test
    @DisplayName("a negative figure is written as a word, so a result's sign does not count: giảm 12,5% is -12.5, "
            + "quá hạn 15 ngày is -15; a date written with dots is not a figure")
    void signsAndDottedDates() {
        List<String> sources = List.of("{\"revenueChangePercent\":-12.5,\"daysToExpiry\":-15,"
                + "\"previous\":{\"orders\":40,\"current\":25}}");

        assertThat(AnswerNumbers.unverified("Doanh thu giảm 12,5%; lô đã quá hạn 15 ngày; số đơn ít hơn 15.", sources))
                .isEmpty();
        assertThat(AnswerNumbers.unverified("Cập nhật ngày 29.10.2026.", List.of())).isEmpty();
    }

    @Test
    @DisplayName("codes, dates and times from the sources are grounded where they stand in text; a code the model "
            + "made up is not")
    void codesDatesAndTimes() {
        List<String> sources = List.of("{\"orderNumber\":\"SO-20991231-900001\",\"lines\":[{\"sku\":\"EVAL-CUP-12OZ\","
                + "\"lotNumber\":\"EVAL-LOT-2401\"}],\"placedAt\":\"2026-10-28T23:59:12+07:00\"}");

        assertThat(AnswerNumbers.unverified("Đơn SO-20991231-900001 đặt lúc 23:59 ngày 28/10, mã EVAL-CUP-12OZ, lô "
                + "EVAL-LOT-2401.", sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Đơn SO-20991231-900077 đã giao.", sources)).containsExactly("900077");
        assertThat(AnswerNumbers.unverified("Mã CUP-12K còn hàng.", List.of("{\"sku\":\"CUP-12K\"}")))
                .as("digits in a code are never thousands").isEmpty();
    }

    @Test
    @DisplayName("the question, its context and a failed call's line are sources too")
    void questionContextAndErrors() {
        List<String> sources = List.of("Lô nào hết hạn trong 45 ngày tới?",
                "Context: today is 2026-10-29, Thursday, now 14:05.",
                "VALIDATION_FAILED: the period is at most 366 days");

        assertThat(AnswerNumbers.unverified("Trong 45 ngày tới, tính từ 29/10, lúc 14:05; kỳ dài nhất 366 ngày.",
                sources)).isEmpty();
        assertThat(AnswerNumbers.unverified("Doanh thu hôm qua 35 triệu.", List.of("doanh thu 35 triệu đúng không?")))
                .as("an amount the user wrote in triệu").isEmpty();
    }

    @Test
    @DisplayName("a list's own numbering is not a figure; each unverified number is listed once, in order")
    void listMarkersAndRepeats() {
        String answer = """
                10. EVAL-CUP-12OZ: 77 cái
                11) EVAL-LID-90: 77 cái, 88 cái""";

        assertThat(AnswerNumbers.unverified(answer, List.of("{\"sku\":[\"EVAL-CUP-12OZ\",\"EVAL-LID-90\"]}")))
                .containsExactly("77", "88");
        assertThat(AnswerNumbers.unverified(null, List.of())).isEmpty();
    }

    @Test
    @DisplayName("grounds combine, and an empty set grounds nothing but small numbers")
    void grounds() {
        AnswerNumbers.Grounds first = AnswerNumbers.Grounds.of(List.of("{\"onHand\":40}"));
        AnswerNumbers.Grounds second = AnswerNumbers.Grounds.of(List.of("{\"onHand\":75}"));

        assertThat(AnswerNumbers.unverified("40 và 75", first.and(second))).isEmpty();
        assertThat(AnswerNumbers.unverified("40 và 75", first)).containsExactly("75");
        assertThat(AnswerNumbers.unverified("40 và 5", AnswerNumbers.Grounds.none())).containsExactly("40");
    }
}
