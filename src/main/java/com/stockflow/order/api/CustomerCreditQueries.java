package com.stockflow.order.api;

import java.util.UUID;

/**
 * A customer's credit position for other modules (the assistant, ADR-0010). Only this module counts the credit orders
 * not yet billed, so only it can say what is left of a limit; the limit less the receivables would overstate it.
 *
 * <p>Call it inside the guard of {@code GET /api/v1/orders/credit-position/{customerId}},
 * {@code @RequiresPermission(resource = "customer-credit-profiles", action = READ)}.</p>
 */
public interface CustomerCreditQueries {

    /** The customer's credit position now; a customer without terms is prepaid only, with no credit allowed. */
    CustomerCredit creditOf(UUID customerId);
}
