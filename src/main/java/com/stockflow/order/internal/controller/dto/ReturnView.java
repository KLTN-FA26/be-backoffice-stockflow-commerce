package com.stockflow.order.internal.controller.dto;

/**
 * A return as one audience may see it: {@link ReturnRequestResponse} for the back office, {@link CustomerReturnResponse}
 * for the customer who owns it. The endpoints both use answer with the caller's own view, so a customer is never handed
 * staff notes, actors or the inspection's findings.
 */
public sealed interface ReturnView permits ReturnRequestResponse, CustomerReturnResponse {
}
