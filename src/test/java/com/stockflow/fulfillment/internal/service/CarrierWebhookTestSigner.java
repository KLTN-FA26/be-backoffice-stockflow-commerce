package com.stockflow.fulfillment.internal.service;

import java.nio.charset.StandardCharsets;

/** Signs a test's WEBHOOK_LOAD event as such a carrier would (QD-11), for tests outside this package. */
public final class CarrierWebhookTestSigner {

    private CarrierWebhookTestSigner() {
    }

    /** {@code sha256=<hex>} of the UTF-8 bytes of {@code content} under {@code secret}. */
    public static String sign(String secret, String content) {
        return WebhookCredentials.sign(secret, content.getBytes(StandardCharsets.UTF_8));
    }
}
