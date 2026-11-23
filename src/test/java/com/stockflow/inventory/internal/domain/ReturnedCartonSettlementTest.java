package com.stockflow.inventory.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.inventory.internal.domain.ReturnedCartonOwnership.Ownership.COMPANY;
import static com.stockflow.inventory.internal.domain.ReturnedCartonOwnership.Ownership.CUSTOMER_HELD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit round 3, RAD-1 (WF-05 Q-4): a return's cartons are settled per SKU and carton, never per return - goods are the
 * company's as far as the refund of their SKU takes them back, and the customer's beyond.
 */
class ReturnedCartonSettlementTest {

    private static ReturnedCartonOwnership.Waiting carton(String lpn, String sku, int quantity) {
        return new ReturnedCartonOwnership.Waiting(new StockPackageId(UUID.randomUUID()), lpn, sku, quantity);
    }

    private static ReturnedCartonSettlement.Decided refund(int back, int refunded) {
        return new ReturnedCartonSettlement.Decided(back, refunded);
    }

    @Test
    @DisplayName("12oz refunded in full, 16oz refused: the 16oz carton stays the customer's although the return "
            + "refunded units")
    void aRefusedSkuStaysTheCustomers() {
        var twelve = carton("LPN-1", "CUP-12", 500);
        var sixteen = carton("LPN-2", "CUP-16", 500);
        var split = ReturnedCartonSettlement.split(List.of(sixteen, twelve),
                Map.of("CUP-12", refund(500, 500), "CUP-16", refund(500, 0)));
        assertThat(split).containsEntry(twelve, COMPANY).containsEntry(sixteen, CUSTOMER_HELD);
    }

    @Test
    @DisplayName("two sealed cartons of 500 back, 500 refunded: the first in label order is the company's, the other "
            + "whole carton the customer's")
    void wholeCartonsBeyondTheRefundStayTheCustomers() {
        var second = carton("LPN-0002", "CUP-12", 500);
        var first = carton("LPN-0001", "CUP-12", 500);
        var split = ReturnedCartonSettlement.split(List.of(second, first), Map.of("CUP-12", refund(1000, 500)));
        assertThat(split).containsEntry(first, COMPANY).containsEntry(second, CUSTOMER_HELD);
    }

    @Test
    @DisplayName("a carton partly refunded is taken whole - a carton is not split (20 BR-14)")
    void aPartlyRefundedCartonIsTakenWhole() {
        var first = carton("LPN-0001", "CUP-12", 500);
        var second = carton("LPN-0002", "CUP-12", 500);
        var split = ReturnedCartonSettlement.split(List.of(first, second), Map.of("CUP-12", refund(1000, 600)));
        assertThat(split).containsEntry(first, COMPANY).containsEntry(second, COMPANY);
    }

    @Test
    @DisplayName("the refund covers what came back outside a sealed carton first (opened cartons, loose units, never "
            + "stocked): 300 loose and a sealed 500 back, 300 refunded - the sealed carton stays the customer's")
    void unstockedGoodsAreCoveredFirst() {
        var sealed = carton("LPN-0001", "CUP-12", 500);
        assertThat(ReturnedCartonSettlement.split(List.of(sealed), Map.of("CUP-12", refund(800, 300))))
                .containsEntry(sealed, CUSTOMER_HELD);
        assertThat(ReturnedCartonSettlement.split(List.of(sealed), Map.of("CUP-12", refund(800, 301))))
                .containsEntry(sealed, COMPANY);
    }

    @Test
    @DisplayName("a SKU the refund does not name refunded nothing: its cartons are the customer's")
    void anUnnamedSkuRefundedNothing() {
        var sealed = carton("LPN-0001", "CUP-12", 500);
        assertThat(ReturnedCartonSettlement.split(List.of(sealed), Map.of())).containsEntry(sealed, CUSTOMER_HELD);
    }
}
