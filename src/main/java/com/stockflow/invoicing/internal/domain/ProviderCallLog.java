package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the log of calls to the provider (plan §1.5; kltn-docs 21 BR-10: every call is logged). Append-only, and written
 * in a transaction of its own so a call is on record even when what it was made for rolls back.
 *
 * <p>It keeps what happened, never what was said: hashes of the request and the response, no payload, no secret and no
 * signed link - an invoice names a buyer, and a log is read by more people than an invoice is.</p>
 */
public interface ProviderCallLog {

    /** What was asked of the provider. */
    enum Operation { SUBMIT, QUERY, DOWNLOAD, NOTICE }

    /** How the call ended: the provider's own answer, or that it gave none. */
    enum Outcome { ACCEPTED, DUPLICATE, PENDING, ISSUED, REJECTED, NOT_FOUND, DOWNLOADED, TIMEOUT, ERROR,
        NOT_SUPPORTED }

    /**
     * @param httpStatus   the provider's HTTP status; null for a provider called in-process (the sandbox)
     * @param errorCode    the provider's error code, when it gave one
     * @param requestHash  SHA-256 of what was sent, lower-case hex; null for a call that sends only the transaction id
     * @param responseHash SHA-256 of what came back; null when nothing did
     * @param providerReference the provider's own handle on a submission it took in (SePay's tracking code, which is
     *                     the only way to learn that it failed after taking it in); null for every other call. An id the
     *                     provider made up, not anything the invoice says
     */
    record Call(UUID einvoiceId, String provider, String transactionId, Operation operation, Outcome outcome,
                Integer httpStatus, String errorCode, String requestHash, String responseHash, Instant calledAt,
                long durationMs, String providerReference) {

        /** A call the provider gave no handle on. */
        public Call(UUID einvoiceId, String provider, String transactionId, Operation operation, Outcome outcome,
                    Integer httpStatus, String errorCode, String requestHash, String responseHash, Instant calledAt,
                    long durationMs) {
            this(einvoiceId, provider, transactionId, operation, outcome, httpStatus, errorCode, requestHash,
                    responseHash, calledAt, durationMs, null);
        }
    }

    void record(Call call);

    /**
     * The handle the provider gave on the latest submission of a transaction it took in; empty when it gave none, or
     * nothing was submitted under the transaction.
     */
    Optional<String> lastProviderReference(String provider, String transactionId);

    /** An invoice's calls, oldest first. */
    List<Call> of(UUID einvoiceId);
}
