package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.PutBackRowResponse;
import com.stockflow.fulfillment.internal.service.PickLists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The put-back queue's row as the front end reads it (team plan 4C-6): {@code quarantine} says whether the carton goes
 * into a quarantine area (removed as damaged at the check station) or onto a bin (a cancelled order's).
 */
class FulfillmentWebMapperTest {

    private final FulfillmentWebMapper mapper = new FulfillmentWebMapper();

    private static PickLists.PutBackRow row(boolean quarantine) {
        return new PickLists.PutBackRow(UUID.randomUUID(), "SO-1", UUID.randomUUID(), UUID.randomUUID(),
                "LY-GIAY-DEMO-12OZ-XANH", "LOT-1", "LPN-1", 1, "HCM-A01-2-B", Instant.parse("2026-11-13T08:00:00Z"),
                quarantine, quarantine ? java.util.List.of() : java.util.List.of("HCM-OVF01"), "PK-20261113-0001",
                "HCM", "Kho Ho Chi Minh");
    }

    @Test
    @DisplayName("a carton removed as damaged is shown with quarantine true, a cancelled order's with false")
    void quarantineIsCarriedToTheResponse() {
        PutBackRowResponse damaged = mapper.toResponse(row(true));
        PutBackRowResponse cancelled = mapper.toResponse(row(false));

        assertThat(damaged.quarantine()).isTrue();
        assertThat(cancelled.quarantine()).isFalse();
        assertThat(damaged.lpn()).isEqualTo("LPN-1");
        assertThat(damaged.fromLocationCode()).isEqualTo("HCM-A01-2-B");
        assertThat(damaged.overflowLocationCodes()).isEmpty();
        assertThat(cancelled.overflowLocationCodes()).as("07 BR-12: the OVERFLOW areas reach the screen")
                .containsExactly("HCM-OVF01");
        assertThat(damaged.taskNumber()).as("the task's human number, not a slice of its UUID")
                .isEqualTo("PK-20261113-0001");
        assertThat(damaged.warehouseCode()).isEqualTo("HCM");
        assertThat(damaged.warehouseName()).as("the screen needs no warehouse list").isEqualTo("Kho Ho Chi Minh");
    }
}
