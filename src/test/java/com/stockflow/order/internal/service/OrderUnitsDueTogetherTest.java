package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.BatchInFlight;
import com.stockflow.order.internal.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-4 fix (4B-8, kltn-docs 17 BR-15): what an order must hand over together is what it still wants less the units of
 * the delivery batches not released yet - a schedule is a plan of whole shipments, so a shipment of exactly the batch in
 * flight leaves nothing behind. The handover, a vehicle leaving and the load plan's confirmation (LP-4) all compare
 * with this one count.
 */
class OrderUnitsDueTogetherTest {

    private static BatchInFlight batchWithLater(int laterUnits) {
        return new BatchInFlight(UUID.randomUUID(), 1, LocalDate.of(2026, 11, 20), laterUnits);
    }

    @Test
    @DisplayName("no batch in flight: everything the order still wants")
    void withoutABatchItIsTheWholeRemainder() {
        Order order = OrderFixtures.released();
        assertThat(order.outstandingUnits()).isEqualTo(15);
        assertThat(order.unitsDueTogether(null)).isEqualTo(order.outstandingUnits());
    }

    @Test
    @DisplayName("a batch in flight: the remainder less the later batches' units")
    void aBatchLeavesTheLaterBatchesOut() {
        Order order = OrderFixtures.released();
        assertThat(order.unitsDueTogether(batchWithLater(5))).isEqualTo(10);
        assertThat(order.unitsDueTogether(batchWithLater(0))).as("the last batch: everything left").isEqualTo(15);
    }

    @Test
    @DisplayName("never below zero, whatever the later batches count")
    void neverNegative() {
        Order order = OrderFixtures.released();
        assertThat(order.unitsDueTogether(batchWithLater(15))).isZero();
        assertThat(order.unitsDueTogether(batchWithLater(40))).isZero();
    }
}
