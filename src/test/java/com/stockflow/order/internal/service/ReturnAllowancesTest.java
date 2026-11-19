package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.ReturnRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** An account that may open no portal return is shown its deliveries' lines with nothing returnable. */
class ReturnAllowancesTest {

    @Test
    @DisplayName("an inactive account sees every line, each with nothing returnable")
    void nothingReturnableKeepsTheLines() {
        UUID shipment = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        var claimable = new ReturnRequest.Claimable(
                Map.of(shipment, List.of(new ReturnRequest.ClaimableLine(line, "CUP-1", 600, 540))),
                List.of(new ReturnRequest.ClaimableLine(line, "CUP-1", 600, 600)));

        ReturnRequest.Claimable shown = ReturnAllowances.nothingReturnable(claimable);

        assertThat(shown.of(shipment)).containsExactly(new ReturnRequest.ClaimableLine(line, "CUP-1", 600, 0));
        assertThat(shown.of(null)).containsExactly(new ReturnRequest.ClaimableLine(line, "CUP-1", 600, 0));
    }
}
