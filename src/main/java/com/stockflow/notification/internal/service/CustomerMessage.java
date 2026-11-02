package com.stockflow.notification.internal.service;

import java.util.Objects;
import java.util.UUID;

/**
 * One customer e-mail an event calls for, before it is addressed: which message ({@code templateCode}), about what
 * ({@code sourceId} - the order, the shipment change, the closure), for whom, and how to word it once the customer's
 * name is known.
 *
 * <p>{@link #reference()} names the message for good. It is the delivery log's operation reference, the success
 * marker's unique {@code external_reference} and the provider's idempotency key, so a redelivered event, a restart's
 * republication and the retry job all arrive at the same message and send it once.</p>
 *
 * @param guestAddress the address an order placed without an account carried; null for a customer's message, which
 *                     is addressed from {@code CustomerDirectory} when it is sent
 */
record CustomerMessage(String templateCode, String sourceId, UUID customerId, String guestAddress, Draft draft) {

    static final String REFERENCE_PREFIX = "customer-mail:";

    /** {@code delivery_log.operation_reference} and {@code external_reference} are VARCHAR(100). */
    static final int MAX_REFERENCE_LENGTH = 100;

    CustomerMessage {
        Objects.requireNonNull(templateCode, "templateCode");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(draft, "draft");
        if ((REFERENCE_PREFIX + templateCode + ":" + sourceId).length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("A customer message reference is at most " + MAX_REFERENCE_LENGTH
                    + " characters");
        }
    }

    /** The words, once the addressee is known. {@code addressee} is the name to greet, null when there is none. */
    @FunctionalInterface
    interface Draft {
        EmailContent write(String addressee);
    }

    String reference() {
        return REFERENCE_PREFIX + templateCode + ":" + sourceId;
    }

    /** The provider tag: the template code in the characters a tag allows. */
    String category() {
        return templateCode.replace('.', '_');
    }

    /** What the log's recipient column says when there is no usable address to put there. */
    String recipientLabel() {
        return customerId != null ? "customer:" + customerId : "guest";
    }

    /** Names the message, never the guest's address. */
    @Override
    public String toString() {
        return "CustomerMessage[" + reference() + ", customer=" + customerId + "]";
    }
}
