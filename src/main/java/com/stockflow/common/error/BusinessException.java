package com.stockflow.common.error;

import com.stockflow.common.api.FieldError;

import java.util.List;

/**
 * Business exception. Breaking a domain invariant throws this class (or a subclass);
 * it never returns null and never throws a bare RuntimeException.
 */
public class BusinessException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * The request fields this refusal is about ({@code lines[1].quantity}), when a business rule can
     * point at them the way Bean Validation does (SCRUM-464). Empty for most refusals.
     */
    private final transient List<FieldError> fieldErrors;

    /**
     * Facts about the refusal a client may show next to its message (SCRUM-193: the credit a customer has left and
     * its currency), keyed by names that belong to the error code. Empty for most refusals.
     */
    private final transient java.util.Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /** One refusal naming the request fields at fault; {@code fieldErrors[].code} says what is wrong with each. */
    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        this(errorCode, message, fieldErrors, java.util.Map.of());
    }

    /** A refusal naming the fields at fault and carrying the facts behind it (values must not be null). */
    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
                             java.util.Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.details = java.util.Map.copyOf(details);
    }

    /**
     * With an underlying cause.
     *
     * <p>Chaining through the constructor rather than {@code initCause} afterwards: calling an
     * overridable method from a constructor is what javac's {@code this-escape} lint warns about,
     * and the warning is right - a subclass could observe a half-built exception.</p>
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.details = java.util.Map.of();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors == null ? List.of() : fieldErrors;
    }

    public java.util.Map<String, Object> details() {
        return details == null ? java.util.Map.of() : details;
    }
}
