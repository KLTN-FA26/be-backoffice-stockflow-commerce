package com.stockflow.common.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A normalised SKU code.
 *
 * <p>Lives in the shared kernel because it is the one identifier that genuinely crosses every
 * module: product declares it, inventory counts it, catalog sells it, fulfilment picks it.
 * Anywhere in the system that holds a {@code Sku} holds a valid one, so no downstream layer has
 * to re-check the format.</p>
 *
 * <p>The alphabet is exactly the database's {@code ck_variants_sku}, which is the single source of
 * what a SKU may be: product codes carry {@code _} and {@code .}, a SKU may be one character long,
 * and none may start with a separator. A narrower pattern here would refuse SKUs the database already
 * holds; a wider one would accept what the insert then rejects.</p>
 */
public record Sku(String code) {

    public static final int MAX_LENGTH = 64;

    /** Compiled once. {@code String.matches()} recompiles the pattern on every call. */
    private static final Pattern PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");

    public Sku {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        code = code.strip().toUpperCase(Locale.ROOT);
        if (!PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Malformed SKU: " + code);
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
