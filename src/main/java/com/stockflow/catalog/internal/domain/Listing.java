package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/** Catalog read snapshot and projection progress; editable SEO is owned by product.products. */
public record Listing(
        UUID productId,
        String slug,
        String seoTitle,
        String seoDescription,
        long revision,
        long projectedRevision,
        boolean enabled,
        boolean everPublished,
        String publishedTitle,
        String publishedDescription,
        String publishedSeoTitle,
        String publishedSeoDescription) {
    public static String normalizeSlug(String input) {
        if (input == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String value =
                Normalizer.normalize(
                                input.trim().toLowerCase(Locale.ROOT).replace('đ', 'd'),
                                Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-|-$", "");
        if (value.isBlank() || value.length() > 255)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value;
    }

    public Listing edit(String slug, String title, String description, long expectedRevision) {
        String normalized = normalizeSlug(slug);
        if (revision != expectedRevision) throw new BusinessException(ErrorCode.CONFLICT);
        if (everPublished && !this.slug.equals(normalized))
            throw new BusinessException(ErrorCode.CONFLICT);
        if (title != null && title.length() > 255
                || description != null && description.length() > 5000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return new Listing(
                productId,
                normalized,
                trim(title),
                trim(description),
                revision + 1,
                projectedRevision,
                enabled,
                everPublished,
                publishedTitle,
                publishedDescription,
                publishedSeoTitle,
                publishedSeoDescription);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
