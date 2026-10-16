package com.stockflow.customer.internal.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Review of #109, item 9: what a list, an invitation response and the customer's own profile show of a tax code. */
class TaxCodeMaskTest {

    @Test
    void keepsTheFirstAndLastThreeAndTheSeparator() {
        assertThat(CustomerWebMapper.maskTaxCode("0312345678")).isEqualTo("031****678");
        assertThat(CustomerWebMapper.maskTaxCode("0312345678-001")).isEqualTo("031*******-001");
        assertThat(CustomerWebMapper.maskTaxCode("001203012345")).as("a 12-digit personal id").isEqualTo("001******345");
    }

    @Test
    void masksShortCodesWholeAndLeavesNoneAsNone() {
        assertThat(CustomerWebMapper.maskTaxCode("123456")).isEqualTo("******");
        assertThat(CustomerWebMapper.maskTaxCode(null)).isNull();
    }
}
