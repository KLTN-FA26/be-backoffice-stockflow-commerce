package com.stockflow.common.http;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * A service we depend on failed or refused.
 *
 * <p>Maps to 502, which is the honest answer: the request was fine, and something we call was not.
 * A 500 would send whoever is on call looking for a bug in this application.</p>
 *
 * @see #isRetryable()
 */
public class ExternalServiceException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String service;
    private final int upstreamStatus;

    public ExternalServiceException(String service, int upstreamStatus, String message) {
        this(service, upstreamStatus, message, null);
    }

    public ExternalServiceException(String service, int upstreamStatus, String message,
                                    Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "%s failed (upstream status %d): %s".formatted(service, upstreamStatus, message),
                cause);
        this.service = service;
        this.upstreamStatus = upstreamStatus;
    }

    public String service() {
        return service;
    }

    /** 0 when the call never got a response - a timeout, a DNS failure, a refused connection. */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    /**
     * Whether retrying could plausibly work.
     *
     * <p>The distinction that matters for a retry policy: a 4xx from upstream means the request was
     * wrong and will be wrong again, so retrying only multiplies the load on a service that is
     * already telling us to stop. A 5xx, a timeout or a connection failure may well succeed on the
     * next attempt. Retrying 4xx is one of the more common ways a client turns a small upstream
     * problem into an outage.</p>
     *
     * <p>429 is the exception among 4xx: it explicitly means "later", not "never".</p>
     */
    public boolean isRetryable() {
        return upstreamStatus == 0 || upstreamStatus >= 500 || upstreamStatus == 429;
    }
}
