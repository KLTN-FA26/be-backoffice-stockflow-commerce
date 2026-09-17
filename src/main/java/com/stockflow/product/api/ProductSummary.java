package com.stockflow.product.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a product master row, for other modules and for the API.
 *
 * <p>Flat and immutable — handing out the {@code Product} aggregate would let a caller invoke
 * {@code updateDetails(...)} on it outside a transaction. {@code images} is empty on a row that
 * came from the paginated list query (see {@code ProductSearchRepository}'s javadoc for why); only
 * a single-product read populates it.</p>
 *
 * <p>{@code submittedBy}/{@code submittedAt}/{@code approvedBy}/{@code approvedAt}/
 * {@code rejectionReason} (SCRUM-57) are null until the corresponding transition has happened.</p>
 */
public record ProductSummary(
        UUID productId,
        String code,
        String name,
        String nameEn,
        UUID categoryId,
        String description,
        String descriptionEn,
        String brand,
        TaxClass taxClass,
        boolean customizable,
        List<String> images,
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
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm
) {

    public ProductSummary {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
