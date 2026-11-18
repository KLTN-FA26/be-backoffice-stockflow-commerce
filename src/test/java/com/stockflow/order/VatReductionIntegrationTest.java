package com.stockflow.order;

import com.stockflow.order.internal.domain.TaxRates;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HĐ-7 against the migrated database (V20261201000200): NĐ 174/2025 reduces the STANDARD rate - the cups and packaging
 * sold here - to 8% from 01/07/2025 to 31/12/2026, both days included; outside it the class's own 10% holds. REDUCED
 * and EXEMPT are untouched. This is what a quote drawn up on a day and an e-invoice dated that day both read.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class VatReductionIntegrationTest {

    @Autowired TaxRates taxRates;

    private BigDecimal standard(LocalDate day) {
        return rate(day, "STANDARD");
    }

    private BigDecimal rate(LocalDate day, String taxClass) {
        Map<String, BigDecimal> rates = taxRates.onDay(day);
        assertThat(rates).as("every class has a rate on " + day).containsKeys("STANDARD", "REDUCED", "EXEMPT");
        return rates.get(taxClass);
    }

    @Test
    @DisplayName("STANDARD is 8% from 01/07/2025 to 31/12/2026 and 10% on either side of it")
    void theReductionOfTwentyTwentySix() {
        assertThat(standard(LocalDate.of(2025, 6, 30))).isEqualByComparingTo("10");
        assertThat(standard(LocalDate.of(2025, 7, 1))).isEqualByComparingTo("8");
        assertThat(standard(LocalDate.of(2026, 11, 18))).isEqualByComparingTo("8");
        assertThat(standard(LocalDate.of(2026, 12, 31))).isEqualByComparingTo("8");
        assertThat(standard(LocalDate.of(2027, 1, 1))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("REDUCED stays 5% and EXEMPT 0% through the reduction")
    void theOtherClassesAreNotReduced() {
        assertThat(rate(LocalDate.of(2026, 11, 18), "REDUCED")).isEqualByComparingTo("5");
        assertThat(rate(LocalDate.of(2026, 11, 18), "EXEMPT")).isEqualByComparingTo("0");
        assertThat(rate(LocalDate.of(2027, 1, 1), "REDUCED")).isEqualByComparingTo("5");
    }
}
