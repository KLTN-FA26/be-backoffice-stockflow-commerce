package com.stockflow.common.domain;

import java.util.regex.Pattern;

/**
 * A normalised SKU code.
 *
 * <p>Lives in the shared kernel because it is the one identifier that genuinely crosses every
 * module: product declares it, inventory counts it, catalog sells it, fulfilment picks it.
 * Anywhere in the system that holds a {@code Sku} holds a valid one, so no downstream layer has
 * to re-check the format.</p>
 */
public record Sku(String code) {

    /** Compiled once. {@code String.matches()} recompiles the pattern on every call. */
    private static final Pattern PATTERN = Pattern.compile("[A-Z0-9\\-]{3,64}");

    public Sku {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        code = code.trim().toUpperCase();
        if (!PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Malformed SKU: " + code);
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
