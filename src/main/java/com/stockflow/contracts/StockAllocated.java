package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when a reservation of an order is allocated to one bin and lot (docs 03
 * §6 HELD → ALLOCATED, 04c F-FUL step 02): at the order's release, and again after a short pick moves an allocation
 * (X-SHORT). One event per allocation; available-to-promise does not change. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's event publication registry. See
 * {@code package-info.java} for the rules on changing this contract.
 *
 * @param reservationId the allocation, the id a pick line consumes
 * @param lotNumber     null for a SKU that is not lot-tracked
 */
public record StockAllocated(
        UUID orderId,
        UUID reservationId,
        String sku,
        String locationCode,
        String lotNumber,
        int quantity
) {
}
