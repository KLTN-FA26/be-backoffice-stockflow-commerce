package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NĐ 254 Phụ lục điểm 4.a: a buyer's name and address may be written with the listed abbreviations where they do not
 * fit - and never lose the house number, street, ward or province.
 */
class InvoiceAbbreviationsTest {

    private static final String NAME = "Công ty Trách nhiệm hữu hạn Sản xuất Bao bì Việt Nam - Chi nhánh Thành phố Hồ Chí Minh";
    private static final String ADDRESS = "12 Nguyễn Huệ, Phường Bến Nghé, Thành phố Hồ Chí Minh, Việt Nam";

    @Test
    @DisplayName("text that fits is left exactly as registered")
    void fittingTextIsUntouched() {
        assertThat(InvoiceAbbreviations.fit(NAME, 200)).isSameAs(NAME);
        assertThat(InvoiceAbbreviations.fit(null, 10)).isNull();
    }

    @Test
    @DisplayName("abbreviations are applied one after the other, only as far as needed")
    void onlyAsFarAsNeeded() {
        // The longest noun first is enough here: nothing else is touched.
        assertThat(InvoiceAbbreviations.fit(NAME, NAME.length() - 10))
                .isEqualTo("Công ty TNHH Sản xuất Bao bì Việt Nam - Chi nhánh Thành phố Hồ Chí Minh");
        assertThat(InvoiceAbbreviations.fit(NAME, 40)).isEqualTo("Công ty TNHH SX Bao bì VN - CN TP Hồ Chí Minh");
    }

    @Test
    @DisplayName("an address keeps its house number, street, ward and province whatever is abbreviated")
    void theAddressKeepsItsParts() {
        String shorter = InvoiceAbbreviations.fit(ADDRESS, 20);
        assertThat(shorter).isEqualTo("12 Nguyễn Huệ, P Bến Nghé, TP Hồ Chí Minh, VN")
                .contains("12 Nguyễn Huệ").contains("Bến Nghé").contains("Hồ Chí Minh");
        assertThat(shorter.length()).as("still longer than asked: nothing is cut to make it fit").isGreaterThan(20);
    }

    @Test
    @DisplayName("whole words in any case; never the middle of another word")
    void wholeWordsOnly() {
        assertThat(InvoiceAbbreviations.fit("PHƯỜNG 5, thành phố Vũng Tàu", 5)).isEqualTo("P 5, TP Vũng Tàu");
        assertThat(InvoiceAbbreviations.fit("Đường Cổ phần hoá, Phườngx 3", 5))
                .as("\"Phườngx\" is not the word \"Phường\"").isEqualTo("Đường CP hoá, Phườngx 3");
    }
}
