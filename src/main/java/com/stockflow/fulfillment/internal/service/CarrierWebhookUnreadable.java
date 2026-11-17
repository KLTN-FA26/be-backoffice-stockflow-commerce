package com.stockflow.fulfillment.internal.service;

/**
 * A webhook body whose credential held but that does not read as the carrier's report: kept, and put on the error list
 * (NEEDS_REVIEW) with this message, which names fields, never values.
 */
final class CarrierWebhookUnreadable extends RuntimeException {

    CarrierWebhookUnreadable(String message) {
        super(message);
    }
}
