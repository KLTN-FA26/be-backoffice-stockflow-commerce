package com.stockflow.fulfillment.internal.service;

/**
 * How the fake Ahamove ({@code stockflow.fulfillment.ahamove.mode=fake}) reports to the system: through the webhooks'
 * own pipeline, as a real callback goes. Its own narrow interface, not a method of {@link CarrierEvents}: only the fake
 * calls it, and no live code path should be able to.
 */
public interface FakeAhamoveCallbacks {

    /**
     * A callback from the fake Ahamove, kept, matched, defended by the report's time window, verified against the
     * carrier's API (the fake's) and applied as a webhook call is - minus the check of the credential and of the
     * caller's address, which an in-process caller has no use for (and which would need the secrets key). The carrier
     * must exist, be API_BOOKING and take webhooks.
     *
     * @return what came of it; not authenticated for a carrier the fake cannot report for
     * @throws IllegalStateException unless the application runs in fake mode
     */
    CarrierEvents.Receipt receiveFromFake(String carrierCode, byte[] body);
}
