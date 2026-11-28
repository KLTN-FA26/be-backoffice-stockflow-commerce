package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How long producing a batch's goods takes when nothing was drawn up for it yet (kltn-docs 17 §4.12 BR-30, B44): the one
 * rule behind both {@code PO_NEEDED} ({@link DelayAlertService}) and the expected delivery date the shortage choice card
 * gives the customer for "wait for production" (17 §4.14 step 56, D48). One place, so the two cannot drift.
 */
@Component
public class ProductionLeadTime {

    private final ProducerRepository producers;
    private final int defaultLeadDays;

    ProductionLeadTime(ProducerRepository producers,
                       @Value("${stockflow.production-schedule.default-lead-days:14}") int defaultLeadDays) {
        if (defaultLeadDays < 0) {
            throw new IllegalArgumentException("stockflow.production-schedule.default-lead-days must not be negative");
        }
        this.producers = producers;
        this.defaultLeadDays = defaultLeadDays;
    }

    /**
     * The standard lead time, in days, of an order whose production orders are {@code production}: the longest of the
     * producers those went to - leaving the cancelled ones out - and the configured default (14 days) while it has none:
     * no producer is chosen for a batch nothing was drawn up for yet.
     */
    public int leadDays(List<ProductionOrder> production) {
        return production.stream().filter(p -> p.status() != ProductionOrderStatus.CANCELLED)
                .map(ProductionOrder::producerId).distinct()
                .map(producers::findById).flatMap(java.util.Optional::stream)
                .mapToInt(Producer::standardLeadDays).max().orElse(defaultLeadDays);
    }
}
