package com.stockflow.order.internal.domain;

import com.stockflow.order.api.DeliveryFailureReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 review N3: goods that came back are held again by whole cartons only. A case owing a part of a carton is
 * refused when it is opened, and what is still to hold is counted in cartons, never in loose units.
 */
class DeliveryFailureCaseTest {

    private final UUID line = UUID.randomUUID();

    private DeliveryFailureCase caseOf(int units) {
        return DeliveryFailureCase.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), "RR-1", UUID.randomUUID(), DeliveryFailureReason.CUSTOMER_ABSENT, null,
                Map.of(line, units), List.of(), Instant.parse("2026-11-12T08:00:00Z"));
    }

    @Test
    void wholeCartonsPassAndALineNotInCartonsCountsByTheUnit() {
        caseOf(4).requireWholeCartons(Map.of(line, 2));
        caseOf(3).requireWholeCartons(Map.of());
    }

    @Test
    void aPartOfACartonIsRefused() {
        assertThatThrownBy(() -> caseOf(3).requireWholeCartons(Map.of(line, 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not whole cartons of 2");
        assertThatThrownBy(() -> caseOf(4).requireWholeCartons(Map.of(line, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatIsStillToHoldIsCountedInCartons() {
        var failure = caseOf(4);

        assertThat(failure.cartonsToHold(line, 2, 0)).isEqualTo(2);
        assertThat(failure.cartonsToHold(line, 2, 2)).isEqualTo(1);
        assertThat(failure.cartonsToHold(line, 2, 4)).as("held whole").isZero();
        assertThat(failure.cartonsToHold(UUID.randomUUID(), 2, 0)).as("not a line of the case").isZero();
        assertThatThrownBy(() -> failure.cartonsToHold(line, 2, 1)).as("a part of a carton held")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still to hold");
        assertThatThrownBy(() -> caseOf(3).cartonsToHold(line, 2, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("came back");
    }
}
