package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The scorer reads answers the way people write them in Vietnamese, and flags numbers nothing supports. */
class GoldenScorerTest {

    @Test
    @DisplayName("codes and names match without regard to case")
    void text() {
        assertThat(GoldenScorer.states("Mã eval-cup-12oz đang thiếu.", "EVAL-CUP-12OZ")).isTrue();
        assertThat(GoldenScorer.states("Nhà cung cấp: Xưởng gỗ Việt", "Xưởng gỗ Việt")).isTrue();
        assertThat(GoldenScorer.states("Mã EVAL-CUP-16OZ", "EVAL-CUP-12OZ")).isFalse();
    }

    @Test
    @DisplayName("numbers match with Vietnamese or English separators, and abbreviated in millions")
    void numbers() {
        assertThat(GoldenScorer.states("Còn 1.250 cái", "1250")).isTrue();
        assertThat(GoldenScorer.states("Còn 1,250 cái", "1250")).isTrue();
        assertThat(GoldenScorer.states("Doanh thu 12.500.000 ₫", "12500000")).isTrue();
        assertThat(GoldenScorer.states("Doanh thu khoảng 12,5 triệu", "12500000")).isTrue();
        assertThat(GoldenScorer.states("Doanh thu khoảng 12,5 triệu", "12540000")).isTrue();
        assertThat(GoldenScorer.states("Doanh thu khoảng 12,5 triệu", "13000000")).isFalse();
        assertThat(GoldenScorer.states("tăng 12,5%", "12.5")).isTrue();
        assertThat(GoldenScorer.states("Còn 40 cái", "400")).isFalse();
    }

    @Test
    @DisplayName("dates match in ISO and in day/month forms")
    void dates() {
        assertThat(GoldenScorer.states("hết hạn ngày 04/11/2026", "2026-11-04")).isTrue();
        assertThat(GoldenScorer.states("hết hạn 4/11", "2026-11-04")).isTrue();
        assertThat(GoldenScorer.states("hết hạn 2026-11-04", "2026-11-04")).isTrue();
        assertThat(GoldenScorer.states("hết hạn 05/11/2026", "2026-11-04")).isFalse();
    }

    @Test
    @DisplayName("a number in the answer that no tool result contains is ungrounded; small numbers and years are not")
    void ungrounded() {
        List<String> sources = List.of("{\"onHand\":40,\"expiryDate\":\"2026-11-04\",\"revenue\":12500000}");
        assertThat(GoldenScorer.ungroundedNumbers(
                "1. Còn 40 cái, hết hạn 04/11/2026; doanh thu 12.500.000 ₫.", sources)).isEmpty();
        assertThat(GoldenScorer.ungroundedNumbers("Còn khoảng 45 cái", sources)).containsExactly("45");
    }

    @Test
    @DisplayName("what the prompt lets the model work out inside one record is grounded; arithmetic across records is "
            + "not")
    void workedOut() {
        List<String> sources = List.of("{\"lines\":[{\"sku\":\"CUP-12OZ\",\"ordered\":5000,\"shipped\":3000,"
                + "\"closed\":500},{\"sku\":\"LID-90\",\"ordered\":800,\"shipped\":0,\"closed\":0}],\"total\":14}");
        assertThat(GoldenScorer.ungroundedNumbers("Còn 1.500 cốc sắp giao", sources)).isEmpty();
        assertThat(GoldenScorer.ungroundedNumbers("Còn 12 PO chưa liệt kê", sources)).isEmpty();
        assertThat(GoldenScorer.ungroundedNumbers("Tổng cộng 5.800 cái", sources)).containsExactly("5.800");
    }
}
