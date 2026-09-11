package com.stockflow.common.validation;

import com.stockflow.common.domain.Sku;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link ValidSku} by asking {@link Sku} itself.
 *
 * <p>Constructing the value object rather than re-implementing its rule is the whole point — there
 * is exactly one definition of a valid SKU, and this is a caller of it. The construction is cheap
 * (a regex match on a short string) and it cannot drift.</p>
 *
 * <p>Null passes. Bean Validation composes: whether the field is required is
 * {@code @NotNull}'s job, and a validator that also rejected null would make
 * {@code @ValidSku} unusable on an optional field.</p>
 */
public class SkuValidator implements ConstraintValidator<ValidSku, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            new Sku(value);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
