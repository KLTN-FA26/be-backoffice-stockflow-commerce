package com.stockflow.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param field   the request field, dotted for nested ones: {@code lines[0].quantity}
 * @param message human-readable and translated; never shown to code
 * @param code    the constraint that failed ({@code NotBlank}, {@code Min}), stable across
 *                translations — this is what a client branches on if it needs to
 */
@Schema(name = "FieldError", description = "One field that failed validation")
public record FieldError(String field, String message, String code) {

    public static FieldError of(String field, String message) {
        return new FieldError(field, message, null);
    }
}
