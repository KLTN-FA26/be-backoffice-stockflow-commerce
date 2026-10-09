package com.stockflow.product.internal.repository;

import java.util.Locale;

/**
 * A slug from a code: lower case, every run of other characters one hyphen, no hyphen at either end
 * — what {@code ck_products_slug}, {@code ck_brands_slug} and {@code ck_categories_slug} accept, and
 * what the SQL function {@code product.slug_of} produced for rows carried over from the old tables.
 */
public final class Slugs {

    private Slugs() {
    }

    public static String of(String code) {
        return code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
