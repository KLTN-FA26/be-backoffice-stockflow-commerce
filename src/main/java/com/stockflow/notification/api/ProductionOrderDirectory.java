package com.stockflow.notification.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a producer is told about a production order, read when the message to it is written: asked by notification when
 * it delivers {@code ProductionOrderSent}, {@code ProductionOrderSampleApproved} or {@code ProductionOrderStopped},
 * <b>implemented by the order module</b> (4A-3).
 *
 * <p>The events carry facts only - an event is stored, and neither the producer's address nor what the coordinator
 * typed belongs in it ({@code verify.py} check 16) - and notification cannot call the order module, which reads the
 * delivery state through {@code notification :: api}: the two would form a cycle. So the dependency is inverted, as
 * {@link CustomerDirectory} is. A module test that boots notification without order mocks this interface.</p>
 */
public interface ProductionOrderDirectory {

    /**
     * The production order as its producer is to read it; empty when there is no such production order. Its links work
     * for the configured time from {@code issuedAt} - the time of the event the message is about - so every attempt at
     * one message writes the same message: a retry under the provider's idempotency key must not differ from an attempt
     * the provider may already have accepted.
     */
    Optional<ProductionOrderMail> mail(UUID productionOrderId, Instant issuedAt);
}
