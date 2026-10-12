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

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /** One refusal naming the request fields at fault; {@code fieldErrors[].code} says what is wrong with each. */
    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
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
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors == null ? List.of() : fieldErrors;
    }
}
