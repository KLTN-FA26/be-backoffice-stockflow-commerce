package com.stockflow.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The field holds a well-formed SKU.
 *
 * <p>An annotation rather than a {@code @Pattern} copied into each DTO, for one reason: the pattern
 * is defined once, in {@link com.stockflow.common.domain.Sku}. A copy on a request record drifts
 * from the value object the moment either changes, and the failure is a request that passes
 * validation and then throws {@code IllegalArgumentException} from the domain — which the exception
 * handler reports as a 400 anyway, but with a message written for a developer rather than a user.</p>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SkuValidator.class)
public @interface ValidSku {

    /**
     * A bundle KEY in braces, not a literal.
     *
     * <p>Hibernate Validator only looks a message up when it is wrapped in braces; a plain string is
     * used verbatim. So the entry for this constraint in {@code i18n/validation*.properties} would
     * be unreachable and the English literal would be shown to every user, in every locale.</p>
     */
    String message() default "{com.stockflow.common.validation.ValidSku.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
