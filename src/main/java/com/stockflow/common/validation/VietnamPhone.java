package com.stockflow.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The field holds a Vietnamese mobile or landline number.
 *
 * <p>Worth validating rather than storing whatever arrives, because the number is used: order
 * confirmations and delivery notifications are sent to it, and a malformed number is a delivery
 * the driver cannot arrange. Catching it on the form is far cheaper than at the door.</p>
 *
 * <p>See {@link VietnamPhoneValidator} for exactly what is accepted, and for what deliberately
 * is not.</p>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = VietnamPhoneValidator.class)
public @interface VietnamPhone {

    /**
     * A bundle KEY in braces, not a literal.
     *
     * <p>Hibernate Validator only looks a message up when it is wrapped in braces; a plain string is
     * used verbatim. So the entry for this constraint in {@code i18n/validation*.properties} would
     * be unreachable and the English literal would be shown to every user, in every locale.</p>
     */
    String message() default "{com.stockflow.common.validation.VietnamPhone.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
