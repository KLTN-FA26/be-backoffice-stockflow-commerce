package com.stockflow.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Accepts Vietnamese numbers in the forms people actually type.
 *
 * <h2>What is accepted</h2>
 * <ul>
 *   <li>{@code 0912345678} — 10 digits, national format, the common case</li>
 *   <li>{@code +84912345678} and {@code 84912345678} — international, with or without the plus</li>
 *   <li>{@code 024 3825 1234} — landlines, and any of the above with spaces, dots or hyphens</li>
 * </ul>
 *
 * <h2>What is not, and why</h2>
 *
 * <p>The old 11-digit mobile numbers are rejected: they were converted to 10 digits in the 2018
 * renumbering and no longer connect. Accepting them would store numbers that silently fail to
 * deliver — worse than rejecting them at the form, where the user can correct it.</p>
 *
 * <p>This checks <b>shape</b>, not existence. It will accept a well-formed number that belongs to
 * nobody. Only sending something to it proves it works, which is what OTP verification is for.</p>
 */
public class VietnamPhoneValidator implements ConstraintValidator<VietnamPhone, String> {

    /**
     * After separators are stripped: a {@code +84}, {@code 84} or national {@code 0} prefix,
     * followed by the significant digits.
     *
     * <p><b>Mobiles and landlines have different lengths</b>, which is the detail that makes a
     * single naive pattern wrong. A mobile is a {@code 3/5/7/8/9} prefix plus 8 digits — 9
     * significant digits, 10 in national form. A landline is a {@code 2xx} area code plus 8 digits
     * — 10 significant digits, 11 in national form. Writing one rule for both, as an earlier
     * version of this class did, silently rejects every landline in the country.</p>
     */
    private static final Pattern NORMALISED =
            Pattern.compile("^(?:\\+?84|0)(?:[35789]\\d{8}|2\\d{9})$");

    /** Separators people type. Stripped before matching, never stored. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;   // presence is @NotBlank's job
        }
        String stripped = SEPARATORS.matcher(value).replaceAll("");
        return NORMALISED.matcher(stripped).matches();
    }

    /**
     * Canonical {@code +84...} form, for storage and for sending.
     *
     * <p>Storing the typed form means the same subscriber appears as {@code 0912345678} and
     * {@code +84912345678} in two rows, and neither a lookup nor a de-duplication finds both.
     * Normalise once, on the way in.</p>
     *
     * @throws IllegalArgumentException if the number is not valid; check with the annotation first
     */
    public static String normalise(String value) {
        String stripped = SEPARATORS.matcher(value == null ? "" : value).replaceAll("");
        if (!NORMALISED.matcher(stripped).matches()) {
            throw new IllegalArgumentException("Not a valid Vietnamese phone number: " + value);
        }
        if (stripped.startsWith("+84")) {
            return stripped;
        }
        if (stripped.startsWith("84")) {
            return "+" + stripped;
        }
        return "+84" + stripped.substring(1);   // drop the national leading zero
    }
}
