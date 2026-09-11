package com.stockflow.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Non-blank, and within a length limit measured <b>after trimming</b>.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>{@code @NotBlank @Size(max = 64)} looks equivalent and is not. {@code @Size} counts the raw
 * string, so {@code "   name   "} passes a 64-character limit at 64 characters of content plus
 * padding — and then the trimmed value is stored into a {@code VARCHAR(64)} column and the insert
 * fails with a constraint violation the user cannot understand. Validating the trimmed length is
 * what makes the annotation's limit and the column's limit the same number.</p>
 *
 * <p>It also rejects control characters. They arrive from copy-paste out of spreadsheets and PDFs,
 * survive every length check, and then break CSV exports, log lines and PDF rendering much further
 * downstream.</p>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBlankTrimmedValidator.class)
public @interface NotBlankTrimmed {

    /**
     * A bundle KEY in braces, not a literal.
     *
     * <p>Hibernate Validator only looks a message up when it is wrapped in braces; a plain string is
     * used verbatim. So the entry for this constraint in {@code i18n/validation*.properties} would
     * be unreachable and the English literal would be shown to every user, in every locale.</p>
     */
    String message() default "{com.stockflow.common.validation.NotBlankTrimmed.message}";

    /** Maximum length after trimming. Match it to the column width. */
    int max() default 255;

    int min() default 1;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
