package com.stockflow.fulfillment.internal.service;

/**
 * A call that could not be handled now but may be later - a passing failure (a deadlock, a lost connection, a lock
 * that waited too long), or a callback for an order whose booking is still being saved. The event stays RECEIVED with
 * this message as its note and the carrier is answered 503, so a carrier that retries delivers it again; a copy that
 * succeeds is applied, the stranded one stays on the error list.
 */
final class CarrierWebhookRetry extends RuntimeException {

    CarrierWebhookRetry(String message) {
        super(message);
    }
}
