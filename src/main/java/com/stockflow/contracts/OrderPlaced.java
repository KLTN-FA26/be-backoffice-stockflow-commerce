package com.stockflow.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderPlaced(
        UUID orderId,
        UUID customerId,
        String contactEmail,
        String orderNumber,
        List<OrderLine> lines,
        BigDecimal totalAmount,
        String currency
) {

    /** One order line. designSnapshotId is only present for print-on-demand items. */
    public record OrderLine(String sku, int quantity, BigDecimal unitPrice, UUID designSnapshotId) {
    }
}
