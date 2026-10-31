package com.stockflow.procurement.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A receiver is shown what is still to come: nothing on a line that is no longer ordered (SCRUM-117). */
class POLineSummaryTest {

    private static POLineSummary line(String status, int ordered, int received) {
        return new POLineSummary(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU-1", "Sofa", "PCS", ordered, received,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, status, false, false, false, "BOX", 4, ordered / 4);
    }

    @ParameterizedTest
    @CsvSource({"OPEN, 8, 0, 8, 2", "PARTIALLY_RECEIVED, 8, 3, 5, 1", "RECEIVED, 8, 8, 0, 0", "CANCELLED, 8, 0, 0, 0",
            "WITHDRAWN, 8, 0, 0, 0", "CLOSED, 8, 3, 0, 0"})
    void openQuantityIsZeroOnceTheLineIsNoLongerOrdered(String status, int ordered, int received, int open,
                                                        int openUnits) {
        POLineSummary line = line(status, ordered, received);
        assertThat(line.openQuantity()).isEqualTo(open);
        assertThat(line.openUnitQuantity()).isEqualTo(openUnits);
    }
}
