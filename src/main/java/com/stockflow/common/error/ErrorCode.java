package com.stockflow.common.error;

/**
 * Shared business error codes. Clients branch on the code, never on the human-readable message.
 *
 * <p><b>Adding a code is a contract change.</b> Every code here can appear in an API response and
 * a client may branch on it, so a code is added freely and renamed never. The message is free to
 * change — it is for humans, and it gets translated.</p>
 *
 * <p>Codes are grouped by the HTTP status they map to, because that mapping is the part clients
 * actually depend on: whether to show a form error, retry, or give up.</p>
 */
public enum ErrorCode {

    // ---- 400: the request itself is wrong; retrying it unchanged will not help
    VALIDATION_FAILED("Invalid request data", 400),
    MALFORMED_REQUEST("The request body could not be read", 400),
    UNSUPPORTED_PARAMETER("Unsupported parameter value", 400),

    // ---- 401 / 403: who the caller is, and what they may do
    UNAUTHORIZED("Not authenticated", 401),
    FORBIDDEN("Not authorised", 403),
    /** Distinct from FORBIDDEN: the action is allowed, but not on these rows. See {@code DataScope}. */
    OUT_OF_DATA_SCOPE("Not authorised for this data", 403),

    // ---- 404
    NOT_FOUND("Resource not found", 404),
    ROLE_NOT_FOUND("Role not found", 404),
    USER_NOT_FOUND("User not found", 404),
    PRODUCT_NOT_FOUND("Product not found", 404),
    CATEGORY_NOT_FOUND("Category not found", 404),

    /** Credentials were correct but the account is LOCKED or DISABLED. Distinct from UNAUTHORIZED,
     *  which covers "wrong username or password" without revealing the account exists. */
    ACCOUNT_NOT_ACTIVE("This account cannot sign in right now", 403),

    // ---- 409: the request is fine, the current state is not
    CONFLICT("Conflicting state", 409),
    INSUFFICIENT_STOCK("Not enough available stock", 409),
    OPTIMISTIC_LOCK("The record changed meanwhile, please retry", 409),
    /** Two writers reached the same row; the loser waited for the lock and gave up. Retryable. */
    LOCK_TIMEOUT("The record is busy, please retry", 409),
    DUPLICATE_KEY("A record with these values already exists", 409),
    /** The same idempotency key is still being processed. The client should poll, not retry blindly. */
    IDEMPOTENT_REQUEST_IN_PROGRESS("An identical request is still being processed", 409),
    PRODUCT_CODE_ALREADY_EXISTS("A product with this code already exists", 409),
    INVALID_PRODUCT_STATUS_TRANSITION("This product cannot move to that status right now", 409),
    SELF_APPROVAL_NOT_ALLOWED("A product cannot be approved by the person who submitted it", 409),

    // ---- 413 / 415: payload problems
    PAYLOAD_TOO_LARGE("The uploaded file is too large", 413),
    UNSUPPORTED_MEDIA_TYPE("This file type is not accepted", 415),

    // ---- 422: understood, but semantically refused
    /** The key was reused for a DIFFERENT request. Replaying the stored response would be a lie. */
    IDEMPOTENCY_KEY_REUSED("This idempotency key was already used for a different request", 422),

    // ---- 429
    RATE_LIMITED("Too many requests", 429),

    // ---- 5xx
    INTERNAL_ERROR("Internal server error", 500),
    /** A dependency we call failed. Separated from INTERNAL_ERROR so alerting can tell them apart. */
    EXTERNAL_SERVICE_ERROR("An upstream service is unavailable", 502),
    STORAGE_ERROR("File storage is unavailable", 503);

    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String defaultMessage, int httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Whether repeating the identical request could plausibly succeed.
     *
     * <p>Exposed so a client — or our own {@code RestClient} retry policy — can decide without
     * hard-coding a list of status codes. A 409 from an optimistic lock is worth retrying; a 409
     * from insufficient stock is not, because nothing about retrying makes stock appear.</p>
     */
    public boolean isRetryable() {
        return switch (this) {
            case OPTIMISTIC_LOCK, LOCK_TIMEOUT, RATE_LIMITED,
                 EXTERNAL_SERVICE_ERROR, STORAGE_ERROR, IDEMPOTENT_REQUEST_IN_PROGRESS -> true;
            default -> false;
        };
    }

    /** 5xx means we broke; 4xx means the caller did. Used to decide log level and alerting. */
    public boolean isServerFault() {
        return httpStatus >= 500;
    }
}
