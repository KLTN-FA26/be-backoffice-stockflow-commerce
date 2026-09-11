package com.stockflow.common.error;

/**
 * The request is well formed but conflicts with the current state — a duplicate code, a status
 * transition that is not allowed, a row somebody else changed first.
 *
 * <p>Distinct from a validation failure: the input is not wrong, the <i>timing</i> or the current
 * state is. Clients treat the two differently — a 400 means "fix the form", a 409 means "reload
 * and try again".</p>
 */
public class ConflictException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        if (errorCode.httpStatus() != 409) {
            throw new IllegalArgumentException(
                    "ConflictException needs a 409 error code, got " + errorCode);
        }
    }
}
