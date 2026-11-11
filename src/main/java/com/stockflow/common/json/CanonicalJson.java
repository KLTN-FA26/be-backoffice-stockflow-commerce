package com.stockflow.common.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.stockflow.common.storage.Checksums;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON Canonicalization Scheme (RFC 8785): one byte sequence for every JSON text that means the same thing, so a
 * browser and this server compute the same checksum of a design document without agreeing on whitespace, key order or
 * how a number is written (kltn-docs 12 BR-08, BR-10: the files a customer submits are bound to the layout they were
 * drawn from by that checksum).
 *
 * <p>The rules, each of which the browser side gets from {@code JSON.stringify} and this class has to reproduce by
 * hand:</p>
 * <ul>
 *   <li>object members sorted by their names compared as UTF-16 code units - {@link String#compareTo} is exactly that,
 *       which is not the same as code-point or locale order once a name leaves the Basic Multilingual Plane;</li>
 *   <li>every number is an IEEE 754 double written as ECMAScript's {@code Number.prototype.toString} writes it:
 *       the shortest digits that round-trip, plain notation from 1e-6 up to 1e21, exponent outside it, {@code -0} as
 *       {@code 0}. {@code 9007199254740993} therefore becomes {@code 9007199254740992}: the browser cannot hold it
 *       either;</li>
 *   <li>strings escaped as {@code JSON.stringify} escapes them: {@code "}, {@code \} and the C0 controls only, the
 *       five with short forms as {@code \b \f \n \r \t}, the rest as {@code \}{@code u00xx}; everything else, line and
 *       paragraph separators included, written as itself;</li>
 *   <li>no whitespace.</li>
 * </ul>
 *
 * <p>Refused, because the RFC leaves them undefined and two implementations would disagree: a duplicate member name, a
 * lone surrogate, and a number outside the double range. The caller answers each as a validation failure.</p>
 *
 * <p>Java's {@link Double#toString} has produced the shortest round-tripping digits since JDK 19 (JDK-4511638); this
 * class only rearranges them into ECMAScript's notation.</p>
 */
public final class CanonicalJson {

    /** Deeper than any layout needs, shallow enough that a hostile body cannot exhaust the stack. */
    static final int MAX_DEPTH = 64;

    /**
     * JSON {@code null} as {@link #parse} returns it: a value, unlike a member that is absent, so a caller can tell the
     * two apart.
     */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
            .build();

    private CanonicalJson() {
    }

    /** A JSON text this class will not canonicalize; the message says why, without echoing the input. */
    public static final class InvalidJsonException extends IllegalArgumentException {
        InvalidJsonException(String message) {
            super(message);
        }
    }

    /**
     * {@code json} in canonical form.
     *
     * @throws InvalidJsonException for text that is not a single JSON value, or that holds a duplicate name, a lone
     *         surrogate or a number no double can hold
     */
    public static String canonicalize(String json) {
        return canonical(parse(json));
    }

    /**
     * {@code json} read as plain Java values, so a caller can inspect a document without a JSON library of its own: an
     * object is a {@code Map<String, Object>} sorted as the canonical form sorts it, an array a {@code List<Object>},
     * a string a {@code String}, a number the {@code Double} the canonical form writes, a boolean a {@code Boolean} and
     * {@code null} {@link #NULL}. The same rules as {@link #canonicalize} decide what is refused.
     *
     * @throws InvalidJsonException as {@link #canonicalize}
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new InvalidJsonException("No JSON text");
        }
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new InvalidJsonException("No JSON text");
            }
            Object value = read(parser);
            if (parser.nextToken() != null) {
                throw new InvalidJsonException("Text continues after the JSON value");
            }
            return value;
        } catch (StreamConstraintsException ex) {
            throw new InvalidJsonException("JSON nested deeper than " + MAX_DEPTH + " levels, or a value too long");
        } catch (JsonParseException ex) {
            // Fixed messages: the parser's own repeat parts of the input.
            String reason = ex.getOriginalMessage();
            throw new InvalidJsonException(reason != null && reason.startsWith("Duplicate field")
                    ? "Duplicate member name" : "Malformed JSON text");
        } catch (IOException ex) {
            throw new InvalidJsonException("Unreadable JSON text");
        }
    }

    /**
     * The canonical form of a value as {@link #parse} returns it, or one built of the same types: maps with string
     * keys (written sorted, whatever their own order), lists, strings, finite numbers, booleans and {@link #NULL}.
     *
     * @throws InvalidJsonException for anything else, or a non-finite number
     */
    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Lower-case hex SHA-256 of {@code canonical}'s UTF-8 bytes: the checksum both sides compare. */
    public static String sha256Hex(String canonical) {
        return Checksums.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static Object read(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> {
                Map<String, Object> members = new TreeMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.currentName();
                    requireWellFormed(name);
                    parser.nextToken();
                    members.put(name, read(parser));
                }
                return members;
            }
            case START_ARRAY -> {
                List<Object> items = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    items.add(read(parser));
                }
                return items;
            }
            case VALUE_STRING -> {
                String text = parser.getText();
                requireWellFormed(text);
                return text;
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                double number = parser.getValueAsDouble();
                if (Double.isNaN(number) || Double.isInfinite(number)) {
                    throw new InvalidJsonException("Number outside the range of a double");
                }
                return number;
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return NULL;
            }
            default -> throw new InvalidJsonException("Unexpected token " + token);
        }
    }

    private static void requireWellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new InvalidJsonException("Lone surrogate in a string");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new InvalidJsonException("Lone surrogate in a string");
            }
        }
    }

    private static void write(Object value, StringBuilder out) {
        if (value == NULL) {
            out.append("null");
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Number number) {
            double finite = number.doubleValue();
            if (Double.isNaN(finite) || Double.isInfinite(finite)) {
                throw new InvalidJsonException("Number outside the range of a double");
            }
            out.append(number(finite));
        } else if (value instanceof String text) {
            requireWellFormed(text);
            string(text, out);
        } else if (value instanceof List<?> items) {
            out.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(items.get(i), out);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> members) {
            // Sorted here rather than trusted: a map a caller built keeps its own order.
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> member : members.entrySet()) {
                if (!(member.getKey() instanceof String name)) {
                    throw new InvalidJsonException("Object member name is not a string");
                }
                requireWellFormed(name);
                sorted.put(name, member.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> member : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        } else {
            throw new InvalidJsonException("Not a JSON value");
        }
    }

    /** ECMAScript's {@code Number.prototype.toString} of a finite double (ECMA-262 Number::toString, radix 10). */
    static String number(double value) {
        if (value == 0) {
            return "0";
        }
        String java = Double.toString(Math.abs(value));
        int e = java.indexOf('E');
        String mantissa = e < 0 ? java : java.substring(0, e);
        int exponent = e < 0 ? 0 : Integer.parseInt(java.substring(e + 1));
        int point = mantissa.indexOf('.');
        String digits = mantissa.substring(0, point) + mantissa.substring(point + 1);
        // n: where the decimal point falls relative to the significant digits (value = 0.d1d2... x 10^n).
        int n = point + exponent;
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
            lead++;
        }
        digits = digits.substring(lead);
        n -= lead;
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        if (digits.length() == 2) {
            // Java never writes fewer than two digits ("4.9E-324"), ECMAScript writes one when one round-trips ("5e-324").
            String single = singleDigit(digits, n, Math.abs(value));
            if (single != null) {
                n += single.length() - 1;
                digits = single.substring(0, 1);
            }
        }
        int k = digits.length();

        StringBuilder out = new StringBuilder(24);
        if (value < 0) {
            out.append('-');
        }
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int shown = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(shown < 0 ? '-' : '+').append(Math.abs(shown));
        }
        return out.toString();
    }

    /**
     * The one-digit decimal that reads back as {@code value}, when Java's two digits {@code digits} (with the point at
     * {@code n}) hide one: the digit below or the digit above, the closer when both do. Returned as {@code "d"}, or as
     * {@code "10"} when rounding up carried into a new digit (the point then moves one place). Only subnormals near
     * {@link Double#MIN_VALUE} have so wide a gap between doubles that this happens.
     */
    private static String singleDigit(String digits, int n, double value) {
        int first = digits.charAt(0) - '0';
        BigDecimal exact = new BigDecimal(value);
        String best = null;
        BigDecimal bestDistance = null;
        for (int candidate : new int[] {first, first + 1}) {
            BigDecimal decimal = BigDecimal.valueOf(candidate).scaleByPowerOfTen(n - 1);
            if (decimal.signum() == 0 || decimal.doubleValue() != value) {
                continue;
            }
            BigDecimal distance = decimal.subtract(exact).abs();
            if (bestDistance == null || distance.compareTo(bestDistance) < 0) {
                best = Integer.toString(candidate);
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
