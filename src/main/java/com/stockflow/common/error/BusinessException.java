package com.stockflow.common.error;

/**
 * Business exception. Breaking a domain invariant throws this class (or a subclass);
 * it never returns null and never throws a bare RuntimeException.
 */
public class BusinessException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
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
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
