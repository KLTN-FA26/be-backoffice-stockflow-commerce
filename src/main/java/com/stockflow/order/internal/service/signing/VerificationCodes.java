package com.stockflow.order.internal.service.signing;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The code printed on a signed contract's certificate page and typed, or scanned as a QR code, on the public
 * verification page. Whoever has it can ask whether a file is the signed contract and read who signed and when, so it is
 * unguessable: 12 characters from 31, about 59 bits, from {@link SecureRandom}. The alphabet leaves out the characters
 * people misread on paper (0 and O, 1 and I and L).
 */
public final class VerificationCodes {

    public static final int LENGTH = 12;
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCodes() {
    }

    public static String newCode() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * What a person typed, as the stored form: upper case, without the spaces and dashes a printed code is grouped
     * with. Null when it cannot be a code (wrong length or characters), so a lookup is not even made for it.
     */
    public static String normalize(String typed) {
        if (typed == null) {
            return null;
        }
        String code = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }

    /** The code as it is printed, in groups of four: {@code ABCD-EFGH-JKMN}. */
    public static String grouped(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8);
    }
}
