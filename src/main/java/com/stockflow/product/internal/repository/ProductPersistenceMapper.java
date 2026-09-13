package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.ProductImageJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.product.api.ProductSummary;

import java.util.List;

/**
 * Translates between the {@code Product} aggregate and its rows.
 *
 * <p>Hand-written rather than MapStruct, same reasoning as {@code StockItemPersistenceMapper}:
 * rehydration has to go through the aggregate's full constructor so its invariants are re-checked,
 * not merely copied field to field.</p>
 */
final class ProductPersistenceMapper {

    private ProductPersistenceMapper() {
    }

    static Product toDomain(ProductJpaEntity entity) {
        List<ProductImage> images = entity.getImages().stream()
                .map(ProductPersistenceMapper::toDomain)
                .toList();

        return new Product(
                new ProductId(entity.getId()),
                entity.getCode(),
                entity.getName(),
                entity.getNameEn(),
                entity.getCategoryId(),
                entity.getDescription(),
                entity.getDescriptionEn(),
                entity.getBrand(),
                entity.getTaxClass(),
                entity.isCustomizable(),
                images,
                entity.getStatus(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getCreatedBy());
    }

    private static ProductImage toDomain(ProductImageJpaEntity entity) {
        return new ProductImage(entity.getId(), entity.getUrl(), entity.getSortOrder());
    }

    /**
     * For the paginated list query only. Deliberately never touches {@code entity.getImages()} —
     * that collection is lazy and the list query does not fetch-join it (see
     * {@code ProductSearchRepository}'s javadoc), so reading it here would either throw outside a
     * session or issue one extra SELECT per row.
     */
    static ProductSummary toSummaryWithoutImages(ProductJpaEntity entity) {
        return new ProductSummary(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getNameEn(),
                entity.getCategoryId(),
                entity.getDescription(),
                entity.getDescriptionEn(),
                entity.getBrand(),
                entity.getTaxClass(),
                entity.isCustomizable(),
                List.of(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getCreatedBy());
    }

    /** Fresh row for an aggregate that has never been persisted. */
    static ProductJpaEntity toNewEntity(Product product) {
        ProductJpaEntity entity = new ProductJpaEntity(
                product.id().value(),
                product.code(),
                product.name(),
                product.nameEn(),
                product.categoryId(),
                product.description(),
                product.descriptionEn(),
                product.brand(),
                product.taxClass(),
                product.status(),
                product.customizable());
        entity.replaceImages(toEntities(product));
        return entity;
    }

    /** Copy the aggregate's state onto a row already managed by the persistence context. */
    static void applyToEntity(Product product, ProductJpaEntity entity) {
        entity.apply(product.name(), product.nameEn(), product.categoryId(), product.description(),
                product.descriptionEn(), product.brand(), product.taxClass(), product.customizable());
        entity.replaceImages(toEntities(product));
    }

    private static List<ProductImageJpaEntity> toEntities(Product product) {
        return product.images().stream()
                .map(image -> new ProductImageJpaEntity(image.id(), image.url(), image.sortOrder()))
                .toList();
    }
}
