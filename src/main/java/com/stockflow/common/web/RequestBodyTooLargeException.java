package com.stockflow.common.web;

import java.io.IOException;

/**
 * Thrown by a capped request's input stream (see {@link RequestBodyLimitFilter}) the moment the body passes its limit.
 *
 * <p>An {@link IOException} because that is what a stream may throw: whoever is reading - the idempotency filter
 * buffering the body, Jackson parsing it - sees it as a failed read, and each turns it into 413 rather than the 400 a
 * malformed body would get.</p>
 */
public final class RequestBodyTooLargeException extends IOException {

    private final long limit;

    public RequestBodyTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }

    /** Whether {@code failure} is, or was caused by, a capped body overflowing. */
    public static boolean causedBy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
