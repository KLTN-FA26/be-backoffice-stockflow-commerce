package com.stockflow.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implements {@link NotBlankTrimmed}. See that annotation for why it exists. */
public class NotBlankTrimmedValidator implements ConstraintValidator<NotBlankTrimmed, String> {

    private int min;
    private int max;

    @Override
    public void initialize(NotBlankTrimmed constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
        if (min > max) {
            throw new IllegalArgumentException(
                    "@NotBlankTrimmed min (%d) must not exceed max (%d)".formatted(min, max));
        }
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;   // unlike @Size, this constraint means "required"
        }
        String trimmed = value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            return false;
        }
        return trimmed.chars().noneMatch(NotBlankTrimmedValidator::isControlCharacter);
    }

    /**
     * Tab, newline and the rest of C0, plus DEL.
     *
     * <p>Deliberately not using {@code Character.isISOControl} alone — the point is to name the
     * range being rejected so the rule is readable, and to be explicit that tab and newline are
     * included. A product name containing a newline breaks a CSV export and a label print.</p>
     */
    private static boolean isControlCharacter(int codePoint) {
        return codePoint < 0x20 || codePoint == 0x7F;
    }
}
