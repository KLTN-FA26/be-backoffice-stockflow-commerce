package com.stockflow.identity.internal.domain;

import java.util.Optional;

/**
 * What a new password must satisfy. One place, so registration, password change and the request
 * validation annotations cannot drift apart.
 *
 * <p>The upper bound is bcrypt's: it hashes only the first 72 bytes, so a longer password would
 * silently be truncated and two different passwords would open the same account.</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 72;
    /** The same rule as a regular expression, for Bean Validation on request records. */
    public static final String PATTERN = "(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+";
    public static final String DESCRIPTION =
            "Password must be %d-%d characters and contain upper-case, lower-case and digit characters"
                    .formatted(MIN_LENGTH, MAX_LENGTH);

    private PasswordPolicy() {
    }

    /** The reason a password is refused, or empty if it is acceptable. */
    public static Optional<String> violation(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
                || password.chars().noneMatch(Character::isUpperCase)
                || password.chars().noneMatch(Character::isLowerCase)
                || password.chars().noneMatch(Character::isDigit)) {
            return Optional.of(DESCRIPTION);
        }
        return Optional.empty();
    }
}
