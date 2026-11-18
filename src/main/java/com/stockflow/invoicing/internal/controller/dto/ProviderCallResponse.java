package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;

/**
 * One call to the e-invoice provider, as logged: what was asked and how it ended - never what was sent.
 * {@code providerReference}: the provider's own handle on a submission it took in (SePay:
 * {@code <create|adjust|replace>/<tracking_code>}), what its support asks for; null otherwise.
 */
public record ProviderCallResponse(String provider, String transactionId, String operation, String outcome,
                                   Integer httpStatus, String errorCode, Instant calledAt, long durationMs,
                                   String providerReference) {
}
