package com.stockflow.notification.internal.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whether an address is worth handing to a provider.
 *
 * <p>Addresses reach this module from other modules' data, some of it older than their validation, and the forms
 * accept what Bean Validation's {@code @Email} accepts - {@code a@b} included. A provider refuses those with a 422 that
 * costs a request and, repeated, sender reputation. Checking first turns a refusal into a recorded skip.</p>
 *
 * <p>The shape is the practical one providers enforce (a dot-separated domain ending in a letters-only label, 254
 * characters at most), not RFC 5322's full grammar: quoted local parts and IP-literal domains are legal and are not
 * mail a business customer gives a supplier. Internationalised addresses are refused for the same reason; the day one
 * is needed, this is the one place to widen.</p>
 */
public final class EmailAddress {

    static final int MAX_LENGTH = 254;

    private static final Pattern SHAPE = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}");

    /**
     * Domains that can never receive mail (RFC 2606 / RFC 6761, and mDNS's {@code .local}). Demo and test data use
     * them on purpose; a real provider delivering to them only collects bounces against the sender's reputation.
     */
    private static final List<String> RESERVED_DOMAINS = List.of("example.com", "example.net", "example.org");
    private static final List<String> RESERVED_SUFFIXES = List.of(".example", ".test", ".invalid", ".localhost",
            ".local");

    private EmailAddress() {
    }

    /** The address, trimmed, when it has a deliverable shape; empty for null, blank or malformed. */
    public static Optional<String> usable(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String address = raw.strip();
        if (address.isEmpty() || address.length() > MAX_LENGTH || !SHAPE.matcher(address).matches()
                || address.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(address);
    }

    /** Whether the address's domain is one that cannot receive mail anywhere (see {@link #RESERVED_DOMAINS}). */
    public static boolean reservedDomain(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        for (String reserved : RESERVED_DOMAINS) {
            if (domain.equals(reserved) || domain.endsWith("." + reserved)) {
                return true;
            }
        }
        for (String suffix : RESERVED_SUFFIXES) {
            if (domain.endsWith(suffix) || domain.equals(suffix.substring(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The address part of a sender written {@code Name <address>} or as a bare address; null when there is none.
     * Configuration is checked with it at startup.
     */
    public static String addressOf(String sender) {
        if (sender == null) {
            return null;
        }
        String value = sender.strip();
        int open = value.lastIndexOf('<');
        if (open >= 0) {
            int close = value.indexOf('>', open);
            if (close < 0 || close != value.length() - 1) {
                return null;
            }
            value = value.substring(open + 1, close).strip();
        }
        return usable(value).orElse(null);
    }
}
