package com.stockflow.notification.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a customer's e-mail goes: asked by notification when it delivers a customer message, <b>implemented by the
 * customer module</b>.
 *
 * <h2>Why the interface is here and the implementation is not</h2>
 *
 * <p>An order's events name the customer by id only. They cannot carry the address: events are stored in
 * {@code event_publication} long after they are delivered, and personal data has no place there ({@code verify.py}
 * check 16b; the stored event names the id and "the listener looks the person up when it delivers"). And notification
 * cannot call {@code customer :: api} to look it up, because customer already depends on {@code notification :: api}
 * for the portal invitation: the two modules would form a cycle, which {@code ModularityTest} refuses.</p>
 *
 * <p>So the dependency is inverted, as payment's {@code OrderPayables} and product's {@code SkuReferenceProbe} already
 * are. Notification declares what it needs, customer - which may depend on {@code notification :: api} - provides it,
 * and at runtime Spring hands notification customer's bean. Neither module gains a dependency it does not already
 * have, and the address is read when the message is sent, never stored on the way. A module test that boots
 * notification without customer mocks this interface.</p>
 */
public interface CustomerDirectory {

    /** The customer's contact for e-mail; empty when there is no such customer. */
    Optional<CustomerContact> contact(UUID customerId);
}
