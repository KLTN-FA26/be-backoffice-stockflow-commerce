package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.util.List;
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
     */
    record Call(UUID einvoiceId, String provider, String transactionId, Operation operation, Outcome outcome,
                Integer httpStatus, String errorCode, String requestHash, String responseHash, Instant calledAt,
                long durationMs) {
    }

    void record(Call call);

    /** An invoice's calls, oldest first. */
    List<Call> of(UUID einvoiceId);
}
