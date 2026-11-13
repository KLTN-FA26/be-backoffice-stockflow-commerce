package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import com.stockflow.support.Await;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

/**
 * Issues an e-invoice and waits for the first call to the provider that {@link EInvoiceIssuance#issue} hands to
 * another thread (WF-05 E3: issuing returns SUBMITTING at once).
 *
 * <p>Calling {@link EInvoiceIssuance#reconcile} instead would not do: while that thread is at the provider, reconcile
 * returns the invoice as it stands, and once it is done a second reconcile asks the provider again - a call the tests
 * that count calls do not expect. So the wait is for what that thread leaves behind: at least one provider call logged
 * for the invoice, and the invoice's provider lease ({@code einvoice_sending.holder}) given up.</p>
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
        // Each target in an Object variable of its own: passed inline, the generic getUltimateTargetObject is inferred
        // as Class<?>, the call binds to getField(Class, String), and the target is cast to Class at run time.
        Object issuanceTarget = AopTestUtils.getUltimateTargetObject(issuance);
        Object steps = ReflectionTestUtils.getField(issuanceTarget, "steps");
        Object stepsTarget = AopTestUtils.getUltimateTargetObject(steps);
        EInvoiceSending sending = (EInvoiceSending) ReflectionTestUtils.getField(stepsTarget, "sending");
        Await.until("the first send of e-invoice " + id + " is over", TIMEOUT,
                () -> !queries.calls(id).isEmpty() && free(sending, id));
        return queries.get(id);
    }

    /**
     * Whether nobody holds the invoice's lease ({@code einvoice_sending.holder IS NULL} or run out): a claim that
     * succeeds says so, and is given up at once.
     */
    private static boolean free(EInvoiceSending sending, UUID id) {
        UUID probe = com.stockflow.common.id.Identifiers.newId();
        if (!sending.claim(id, probe, Duration.ofSeconds(30))) {
            return false;
        }
        sending.release(id, probe);
        return true;
    }
}
