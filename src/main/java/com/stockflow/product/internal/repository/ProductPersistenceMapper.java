package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.entity.ProductJpaEntity;

/**
 * Hand-written entity⇄domain mapping. Rehydration goes through the aggregate's real constructor, so
 * a row that breaks an invariant fails loudly on load instead of becoming a half-valid aggregate —
 * the same reasoning as {@code StockItemPersistenceMapper}.
 */
final class ProductPersistenceMapper {

    private ProductPersistenceMapper() {
    }

    static Product toDomain(ProductJpaEntity e) {
        return new Product(new ProductId(e.getId()), e.getCode(), e.getName(), e.getNameEn(), e.getBrandId(),
                e.getCategoryId(), e.getShortDescription(), e.getDescription(), e.getDescriptionEn(),
                e.getTaxClass(), e.getKind(), e.getStatus(), e.getVersion(), e.getSubmittedBy(),
                e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.getRejectionReason(),
                e.getDiscontinuedAt());
    }

    /** Everything the aggregate owns. The primary category is not a column of this row; the
     *  adapter writes it to {@code product_categories}. */
    static void applyToEntity(Product p, ProductJpaEntity e) {
        e.setDetails(p.name(), p.nameEn(), p.brandId(), p.shortDescription(), p.description(), p.descriptionEn(),
                p.taxClass(), p.kind());
        e.setWorkflow(p.status(), p.submittedBy(), p.submittedAt(), p.approvedBy(), p.approvedAt(),
                p.rejectionReason(), p.discontinuedAt());
    }

    static ProductSummary toSummary(ProductJpaEntity e) {
        return new ProductSummary(e.getId(), e.getCode(), e.getName(), e.getNameEn(), e.getSlug(), e.getBrandId(),
                e.getBrandName(), e.getCategoryId(), e.getShortDescription(), e.getDescription(),
                e.getDescriptionEn(), e.getTaxClass(), e.getKind(), e.getStatus(), e.getCreatedAt(),
                e.getCreatedBy(), e.getLastModifiedAt(), e.getLastModifiedBy(), e.getSubmittedBy(),
                e.getSubmittedAt(), e.getApprovedBy(), e.getApprovedAt(), e.getRejectionReason(),
                e.getPublishedAt(), e.getDiscontinuedAt());
    }
}
