package com.stockflow.invoicing.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: when an invoice was last sent to the provider, under which transaction id, and who is talking to the provider
 * about it right now ({@code invoicing.einvoice_sending}).
 *
 * <p>The quiet period before a transaction the provider does not know is sent again runs from the last send, so the
 * last send has to be known; and it is recorded <b>before</b> the provider is called, so a send the application did
 * not live to see the end of still counts. Kept apart from the invoice: a send does not change the invoice, and must
 * not advance the version the accountant acts on.</p>
 *
 * <p><b>The lease.</b> Only the holder of an invoice's lease asks the provider about it or sends it. Two senders of one
 * transaction at once is how a provider issues two invoices for it, and the senders may be on different instances: the
 * job, the accountant's "check again", the send that follows "issue". The lease is in the database for that reason,
 * timed by the database's clock, and expires on its own, so an instance that dies holding it blocks nobody for
 * long.</p>
 */
public interface EInvoiceSending {

    /** When the invoice was last sent under {@code transactionId}; empty when it never was. */
    Optional<Instant> lastSent(UUID einvoiceId, String transactionId);

    /** Records that the invoice is sent now under {@code transactionId}, replacing what an earlier send recorded. */
    void sent(UUID einvoiceId, String transactionId, Instant at);

    /**
     * Takes the invoice's lease for {@code lease}, when nobody holds it or the holder's has run out.
     *
     * @return whether {@code holder} now holds it
     */
    boolean claim(UUID einvoiceId, UUID holder, Duration lease);

    /**
     * Extends {@code holder}'s lease to {@code lease} from now, as long as nobody took it over meanwhile - which also
     * holds when it ran out and nobody wanted it.
     *
     * @return whether {@code holder} still holds it
     */
    boolean renew(UUID einvoiceId, UUID holder, Duration lease);

    /** Gives up {@code holder}'s lease; nothing when somebody else holds it by now. */
    void release(UUID einvoiceId, UUID holder);
}
