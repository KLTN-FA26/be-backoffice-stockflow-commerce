package com.stockflow.product.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The key two product names are compared by when looking for duplicates (SCRUM-88). */
class ProductNameKeyTest {

    @Test
    @DisplayName("case, Vietnamese diacritics, spaces and punctuation do not tell two names apart")
    void spellingsOfOneNameShareAKey() {
        assertThat(ProductNameKey.of("Ly giấy 12oz")).isEqualTo("lygiay12oz");
        assertThat(ProductNameKey.of("ly giay  12OZ")).isEqualTo("lygiay12oz");
        assertThat(ProductNameKey.of("LY GIẤY 12 OZ")).isEqualTo("lygiay12oz");
        assertThat(ProductNameKey.of("Ly-giấy 12oz.")).isEqualTo("lygiay12oz");
    }

    @Test
    @DisplayName("đ, which has no decomposition, becomes d")
    void dStrokeIsD() {
        assertThat(ProductNameKey.of("Đĩa đựng")).isEqualTo("diadung");
    }

    @Test
    @DisplayName("a different size is a different key")
    void sizeMatters() {
        assertThat(ProductNameKey.of("Ly giấy 16oz")).isNotEqualTo(ProductNameKey.of("Ly giấy 12oz"));
    }

    @Test
    @DisplayName("nothing but spaces or punctuation, or no name at all, is the empty key")
    void emptyKey() {
        assertThat(ProductNameKey.of("  ")).isEmpty();
        assertThat(ProductNameKey.of("--")).isEmpty();
        assertThat(ProductNameKey.of(null)).isEmpty();
    }
}
