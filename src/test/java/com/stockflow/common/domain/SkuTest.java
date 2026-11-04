package com.stockflow.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkuTest {

    @Test
    void theSkuAlphabetIsTheDatabases() {
        assertThat(new Sku(" cup_pet ").code()).isEqualTo("CUP_PET");
        assertThat(new Sku("LID.80").code()).isEqualTo("LID.80");
        assertThat(new Sku("A").code()).isEqualTo("A");
        assertThat(new Sku("x".repeat(64)).code()).hasSize(64);
        assertThatThrownBy(() -> new Sku("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sku("-AB")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sku("A B")).isInstanceOf(IllegalArgumentException.class);
    }
}
