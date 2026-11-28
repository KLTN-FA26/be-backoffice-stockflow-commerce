package com.stockflow.fulfillment.internal.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a carrier's refusal says, in words a dispatcher can act on (WF4 UX audit S17-2, kltn-docs 09 §4.2 step 6 and its
 * edge cases: the carrier may refuse an order and the load then stays {@code PLANNED}).
 *
 * <p>Ahamove answers a refused call with an error code and a few English words: {@code 400 INVALID_LOCATION: Failed},
 * {@code 406 INVALID_SERVICE_HOUR: Booking time not valid}. Neither says what to do or who does it. Each code the
 * carrier is known to send has a message in the bundles under {@link #KEY_PREFIX} + the code, saying what to check, in
 * which field, and who acts; a code with no entry keeps the carrier's own words, as before. The raw text stays what
 * is stored and logged (the booking's {@code last_error}, the call log): only what is shown is translated.</p>
 */
public final class CarrierRefusals {

    /** The bundle key of a carrier error code's message: {@code carrier.refusal.INVALID_LOCATION}. */
    static final String KEY_PREFIX = "carrier.refusal.";

    /** An error code as a carrier writes it, and the only shape a bundle key is built from. */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    /** The described refusal {@code AhamoveGateway} writes: {@code "<status> <CODE>[: <words>]"}. */
    private static final Pattern DESCRIBED = Pattern.compile("^\\d{3} ([A-Z][A-Z0-9_]{2,63})(?::.*)?$", Pattern.DOTALL);

    private CarrierRefusals() {
    }

    /** The bundle key for {@code code}, or null when it is no error code (null, lower case, a sentence). */
    public static String keyOf(String code) {
        return code != null && CODE.matcher(code).matches() ? KEY_PREFIX + code : null;
    }

    /** The error code a described refusal ({@code "400 INVALID_LOCATION: Failed"}) names, or null when it names none. */
    public static String codeOf(String described) {
        if (described == null) {
            return null;
        }
        Matcher match = DESCRIBED.matcher(described.strip());
        return match.matches() ? match.group(1) : null;
    }
}
