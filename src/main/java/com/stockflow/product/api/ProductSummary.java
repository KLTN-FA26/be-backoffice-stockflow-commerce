package com.stockflow.product.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a product master row ({@code product.products}), for other modules and for the API.
 *
 * <p>Flat and immutable — handing out the {@code Product} aggregate would let a caller invoke
 * {@code updateDetails(...)} on it outside a transaction. {@code categoryId} is the product's primary
 * category; {@code brandName} is read alongside {@code brandId} so a list screen needs no second
 * call. {@code slug}, {@code publishedAt} and {@code discontinuedAt} are read-only here: the slug is
 * edited with the selling content, publication is decided by the catalog.</p>
 *
 * <p>{@code submittedBy}/{@code submittedAt}/{@code approvedBy}/{@code approvedAt}/
 * {@code rejectionReason} (SCRUM-57) are null until the corresponding transition has happened.</p>
 */
public record ProductSummary(
        UUID productId,
        String code,
        String name,
        String nameEn,
        String slug,
        UUID brandId,
        String brandName,
        UUID categoryId,
        String shortDescription,
        String description,
        String descriptionEn,
        TaxClass taxClass,
        ProductKind kind,
        ProductStatus status,
        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,
        UUID submittedBy,
        Instant submittedAt,
        UUID approvedBy,
        Instant approvedAt,
        String rejectionReason,
        Instant publishedAt,
        Instant discontinuedAt
) {
}
