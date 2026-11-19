package com.stockflow.common.error;

import com.stockflow.common.api.FieldError;

import java.util.List;

/**
 * Business exception. Breaking a domain invariant throws this class (or a subclass);
 * it never returns null and never throws a bare RuntimeException.
 *
 * <h2>The message, in the caller's language</h2>
 *
 * <p>The exception's own message is English and written for a log. A refusal a person reads in the application - a
 * quote below its MOQ, a payment plan that does not add up - may also name a {@link #messageKey() message key} of the
 * {@code i18n/messages} bundles and the arguments it is filled with ({@link #localized}); the exception handler then
 * answers in the caller's language (Vietnamese unless they ask for English), the English message being the fallback
 * should a key ever be missing. The domain stays free of Spring: it names the key, the web layer translates it.</p>
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

    /** The bundle key of the message a person reads, or null to answer with the message itself. */
    private final String messageKey;

    /** What {@link #messageKey} is filled with, {@code {0}} first; an enum reads as its label in the bundle. */
    private final transient Object[] messageArguments;

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
        this(errorCode, message, fieldErrors, details, null);
    }

    /**
     * The same, with the message a person reads named by {@code messageKey} and filled with {@code messageArguments}
     * (see the class comment); {@code message} stays the English text of the log and the fallback.
     */
    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
                             java.util.Map<String, Object> details, String messageKey, Object... messageArguments) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.details = java.util.Map.copyOf(details);
        this.messageKey = messageKey;
        this.messageArguments = messageArguments == null ? new Object[0] : messageArguments.clone();
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
        this.messageKey = null;
        this.messageArguments = new Object[0];
    }

    /**
     * This refusal, its message read by people from the bundle key {@code key} filled with {@code arguments}:
     * {@code throw new BusinessException(code, "English for the log").localized("quote.belowMoq", line, moq)}.
     * Only for a plain {@code BusinessException} - a subclass, whose type a caller may catch, passes its key to the
     * constructor instead, so that nothing it is gets lost in a copy.
     *
     * @throws IllegalStateException on a subclass
     */
    public BusinessException localized(String key, Object... arguments) {
        if (getClass() != BusinessException.class) {
            throw new IllegalStateException(getClass().getSimpleName() + " passes its message key to the constructor");
        }
        BusinessException localized = new BusinessException(errorCode, getMessage(), fieldErrors(), details(),
                java.util.Objects.requireNonNull(key, "key"), arguments);
        localized.setStackTrace(getStackTrace());
        return localized;
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

    /** The bundle key of the message people read; null when the message itself is what they read. */
    public String messageKey() {
        return messageKey;
    }

    /** The arguments of {@link #messageKey()}, a copy; empty without a key. */
    public Object[] messageArguments() {
        return messageArguments == null ? new Object[0] : messageArguments.clone();
    }
}
