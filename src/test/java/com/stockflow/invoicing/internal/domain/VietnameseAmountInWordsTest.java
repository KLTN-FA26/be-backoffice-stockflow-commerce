package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The total "bằng chữ" of an invoice (NĐ 254 Phụ lục điểm 5.d): every turn the spoken number takes. */
class VietnameseAmountInWordsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0             | Không đồng
            1             | Một đồng
            4             | Bốn đồng
            10            | Mười đồng
            11            | Mười một đồng
            15            | Mười lăm đồng
            20            | Hai mươi đồng
            21            | Hai mươi mốt đồng
            24            | Hai mươi bốn đồng
            25            | Hai mươi lăm đồng
            55            | Năm mươi lăm đồng
            100           | Một trăm đồng
            101           | Một trăm lẻ một đồng
            105           | Một trăm lẻ năm đồng
            110           | Một trăm mười đồng
            115           | Một trăm mười lăm đồng
            999           | Chín trăm chín mươi chín đồng
            1000          | Một nghìn đồng
            1005          | Một nghìn không trăm lẻ năm đồng
            1050          | Một nghìn không trăm năm mươi đồng
            1500          | Một nghìn năm trăm đồng
            21000         | Hai mươi mốt nghìn đồng
            1250000       | Một triệu hai trăm năm mươi nghìn đồng
            1005000       | Một triệu không trăm lẻ năm nghìn đồng
            1000001       | Một triệu không trăm lẻ một đồng
            2200000       | Hai triệu hai trăm nghìn đồng
            123456789     | Một trăm hai mươi ba triệu bốn trăm năm mươi sáu nghìn bảy trăm tám mươi chín đồng
            1000000000    | Một tỷ đồng
            1000000005    | Một tỷ không trăm lẻ năm đồng
            2500000000    | Hai tỷ năm trăm triệu đồng
            15000000000   | Mười lăm tỷ đồng
            1000000000000 | Một nghìn tỷ đồng
            """)
    void readsTheAmount(String amount, String words) {
        assertThat(VietnameseAmountInWords.of(new BigDecimal(amount))).isEqualTo(words);
    }

    @Test
    @DisplayName("a scale of zeros is still whole dong; below zero starts with Âm; a fraction is refused")
    void scaleSignAndFractions() {
        assertThat(VietnameseAmountInWords.of(new BigDecimal("1250000.0000")))
                .isEqualTo("Một triệu hai trăm năm mươi nghìn đồng");
        assertThat(VietnameseAmountInWords.of(new BigDecimal("-110000"))).isEqualTo("Âm một trăm mười nghìn đồng");
        assertThatThrownBy(() -> VietnameseAmountInWords.of(new BigDecimal("10.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
