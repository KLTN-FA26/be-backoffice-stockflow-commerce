package com.stockflow.identity.internal.service;

import java.security.SecureRandom;

/**
 * Generates the password an administrator hands to a new or reset account.
 *
 * <p>Sixteen characters from an alphabet without look-alikes (0/O, 1/l/I), always with an upper-case
 * letter, a lower-case letter and a digit, so it meets {@code PasswordPolicy} and can be read out
 * over the phone. The user must replace it at first sign-in.</p>
 */
final class TemporaryPasswords {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    static String generate() {
        char[] password = new char[LENGTH];
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(DIGITS);
        for (int i = 3; i < LENGTH; i++) {
            password[i] = pick(ALL);
        }
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char swap = password[i];
            password[i] = password[j];
            password[j] = swap;
        }
        return new String(password);
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
