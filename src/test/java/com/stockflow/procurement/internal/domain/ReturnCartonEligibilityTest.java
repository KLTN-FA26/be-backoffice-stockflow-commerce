package com.stockflow.procurement.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.stockflow.procurement.internal.domain.ReturnCartonEligibility.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReturnCartonEligibilityTest {

    @Test
    @DisplayName("the carton's own state, in the order the scan refuses: in the warehouse, opened before, the customer's")
    void cartonState() {
        assertThat(ofCarton("IN_STOCK", Instant.now(), true)).isEqualTo(IN_WAREHOUSE);
        assertThat(ofCarton("LOST", null, false)).isEqualTo(IN_WAREHOUSE);
        assertThat(ofCarton("CONSUMED", Instant.now(), true)).isEqualTo(RETURNED_OPENED);
        assertThat(ofCarton("CONSUMED", null, true)).isEqualTo(CUSTOMER_GOODS);
        assertThat(ofCarton("CONSUMED", null, false)).isNull();
    }

    @Test
    @DisplayName("against the approval: not approved, reached, fits sealed, or needs a count - never dropped for its "
            + "label alone")
    void units() {
        assertThat(ofUnits(0, 0, 20)).isEqualTo(SKU_NOT_APPROVED);
        assertThat(ofUnits(20, 0, 20)).isEqualTo(APPROVED_QUANTITY_REACHED);
        assertThat(ofUnits(20, 20, 20)).isEqualTo(RECEIVABLE);
        assertThat(ofUnits(15, 15, 20)).isEqualTo(COUNT_REQUIRED);
        assertThat(maxReceivable(RECEIVABLE, 40, 20)).isEqualTo(20);
        assertThat(maxReceivable(COUNT_REQUIRED, 15, 20)).isEqualTo(15);
        assertThat(maxReceivable(SKU_NOT_APPROVED, 15, 20)).isZero();
    }

    @Test
    @DisplayName("only what may be worked on now is a candidate")
    void candidates() {
        assertThat(RECEIVABLE.candidate()).isTrue();
        assertThat(COUNT_REQUIRED.candidate()).isTrue();
        assertThat(ON_THIS_RECEIPT.candidate()).isTrue();
        assertThat(IN_WAREHOUSE.candidate()).isFalse();
        assertThat(APPROVED_QUANTITY_REACHED.candidate()).isFalse();
    }
}
