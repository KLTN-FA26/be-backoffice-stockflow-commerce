package com.stockflow.product.internal.repository;

import java.util.Locale;

/**
 * A slug from a code: lower case, every run of other characters one hyphen, no hyphen at either end
 * — what {@code ck_products_slug}, {@code ck_brands_slug} and {@code ck_categories_slug} accept. Rows
 * carried over from the old tables (V20261011000200) got their slugs from the same rule in SQL.
 */
public final class Slugs {

    private Slugs() {
    }

    public static String of(String code) {
        return code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
