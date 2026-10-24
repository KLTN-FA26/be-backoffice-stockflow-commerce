package com.stockflow.notification.internal.domain;

/**
 * The one way out for e-mail. Supplier purchase orders, staff notices, the portal invitation and customer messages
 * all go through it; which provider sits behind it is {@code stockflow.notification.email.provider}.
 *
 * <p>The contract every implementation keeps, because the callers' retry decisions rest on it:</p>
 * <ul>
 *   <li>it returns only when the provider accepted the message;</li>
 *   <li>it fails with {@link EmailDeliveryException} and says which kind of failure: worth retrying (a timeout, a 5xx,
 *       a rate limit, with the delay the provider asked for), refused for this message (an invalid recipient), or
 *       refused for every message (a bad key, an unverified sender) - the last two are never retried;</li>
 *   <li>it never logs a subject, a body, an address or a credential.</li>
 * </ul>
 */
public interface EmailTransport {

    EmailProvider provider();

    /**
     * @throws EmailDeliveryException when the message was not accepted
     */
    EmailReceipt send(Email email);
}
