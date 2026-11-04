package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The key that makes a combination of options unique on a product ({@code ATTR=VALUE;ATTR=VALUE},
 * attribute codes ascending, value codes from the dictionary). The client never sends one: it is
 * derived from the options, so two requests for the same combination collide on the database's
 * unique index instead of creating twins.
 *
 * <p>A product with no axes has no combination to key on, so it uses two special forms:
 * {@code DEFAULT} for its default variant and {@code SKU=<code>} for each extra one.</p>
 */
public record VariantSignature(String value) {

    private static final int MAX_LENGTH = 512;
    private static final String DEFAULT = "DEFAULT";

    public VariantSignature {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A variant signature is 1 to %d characters".formatted(MAX_LENGTH));
        }
    }

    public static VariantSignature of(Map<String, String> attributeCodeToValueCode) {
        return new VariantSignature(new TreeMap<>(attributeCodeToValueCode).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";")));
    }

    public static VariantSignature defaultSignature() {
        return new VariantSignature(DEFAULT);
    }

    public static VariantSignature ofSku(Sku sku) {
        return new VariantSignature("SKU=" + sku.code());
    }

    public boolean isDefault() {
        return DEFAULT.equals(value);
    }
}
