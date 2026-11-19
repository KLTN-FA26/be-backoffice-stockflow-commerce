package com.stockflow.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One rejected field, so a form can put the message next to the input that caused it.
 *
 * <h2>Why this is a list and not a joined string</h2>
 *
 * <p>The first version of {@code GlobalExceptionHandler} joined every violation into
 * {@code "quantity: must be at least 1; sku: is required"}. It reads fine in a log and is useless in
 * a browser: the frontend has to split on {@code "; "}, then on {@code ": "}, and hope no message
 * ever contains either — and a validation message quite reasonably might. The result is either a
 * single banner saying everything at once, or a parser that breaks the first time somebody rewords
 * a message.</p>
 *
 * <p>Structured, the frontend does {@code errors.find(e =&gt; e.field === 'quantity')} and the
 * message goes under the field. That is the difference between a form a user can fix and a form
 * that just says no.</p>
 *
 * <h2>Translated where it is answered</h2>
 *
 * <p>A business rule written in the domain (no Spring there) names the bundle key of its message and the values it
 * is filled with ({@link #localized}); the exception handler translates it into the caller's language and answers
 * with the three wire fields only - {@code messageKey} and {@code messageArguments} never leave the server.</p>
 *
 * @param field            the request field, dotted for nested ones: {@code lines[0].quantity}
 * @param message          human-readable and translated; never shown to code (English until translated)
 * @param code             the constraint that failed ({@code NotBlank}, {@code Min}), stable across
 *                         translations — this is what a client branches on if it needs to
 * @param messageKey       the bundle key of {@code message}, or null; not serialised
 * @param messageArguments what {@code messageKey} is filled with; not serialised
 */
@Schema(name = "FieldError", description = "One field that failed validation")
public record FieldError(String field, String message, String code,
                         @JsonIgnore @Schema(hidden = true) String messageKey,
                         @JsonIgnore @Schema(hidden = true) List<Object> messageArguments) {

    public FieldError {
        messageArguments = messageArguments == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messageArguments));
    }

    public FieldError(String field, String message, String code) {
        this(field, message, code, null, null);
    }

    public static FieldError of(String field, String message) {
        return new FieldError(field, message, null);
    }

    /** A field at fault whose message people read from the bundle key {@code messageKey}, filled with the arguments. */
    public static FieldError localized(String field, String message, String code, String messageKey,
                                       Object... messageArguments) {
        return new FieldError(field, message, code, java.util.Objects.requireNonNull(messageKey, "messageKey"),
                messageArguments == null ? null : Arrays.asList(messageArguments));
    }
}
