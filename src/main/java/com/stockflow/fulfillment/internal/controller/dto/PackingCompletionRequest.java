package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Completing packing: the sealed packages not shipped yet become one shipment. The body is optional; without it the
 * whole order is expected (kltn-docs 08 BR-01).
 *
 * @param requestId the caller's id for this completion: sent again, it answers with the shipment it made
 * @param partial   ship what is sealed although the order wants more (split shipments, G4-Q3)
 * @param note      why only part goes; required with {@code partial}
 */
public record PackingCompletionRequest(UUID requestId, boolean partial, @Size(max = 500) String note) {
}
