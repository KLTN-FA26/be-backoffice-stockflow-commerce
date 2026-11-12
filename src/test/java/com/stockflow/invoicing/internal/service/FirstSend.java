package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.support.Await;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Issues an e-invoice and waits for the first call to the provider that {@link EInvoiceIssuance#issue} hands to
 * another thread (WF-05 E3: issuing returns SUBMITTING at once).
 *
 * <p>Calling {@link EInvoiceIssuance#reconcile} instead would not do: while that thread is at the provider, reconcile
 * returns the invoice as it stands, and once it is done a second reconcile asks the provider again - a call the tests
 * that count calls do not expect. So the wait is for what that thread leaves behind: at least one provider call logged
 * for the invoice, and the invoice no longer held by any thread of this instance.</p>
 */
public final class FirstSend {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private FirstSend() {
    }

    /** {@link EInvoiceIssuance#issue}, then the invoice as the first send left it: ISSUED, REJECTED or SUBMITTING. */
    public static EInvoice.Snapshot issue(EInvoiceIssuance issuance, EInvoiceQueryService queries, UUID id,
                                         Long expectedVersion, UUID by) {
        issuance.issue(id, expectedVersion, by);
        return awaited(issuance, queries, id);
    }

    /** The invoice once the send already handed to another thread - by the accountant or the job - is over. */
    public static EInvoice.Snapshot awaited(EInvoiceIssuance issuance, EInvoiceQueryService queries, UUID id) {
        // Typed as Object on purpose: left generic, the call infers Class<?> and picks getField(Class, String).
        Object target = AopTestUtils.getUltimateTargetObject(issuance);
        @SuppressWarnings("unchecked")
        Set<UUID> inFlight = (Set<UUID>) ReflectionTestUtils.getField(target, "inFlight");
        Await.until("the first send of e-invoice " + id + " is over", TIMEOUT,
                () -> !queries.calls(id).isEmpty() && !inFlight.contains(id));
        return queries.get(id);
    }
}
