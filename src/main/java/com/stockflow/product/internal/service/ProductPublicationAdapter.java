package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.PublicationProduct;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.SkuJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ProductPublicationAdapter implements ProductPublication {
    private final ProductJpaRepository products;
    private final SkuJpaRepository skus;
    private final ProductPublicationService publication;
    private final EntityManager entities;
    ProductPublicationAdapter(ProductJpaRepository products, SkuJpaRepository skus,
            ProductPublicationService publication, EntityManager entities) {
        this.products=products; this.skus=skus; this.publication=publication; this.entities=entities;
    }
    public PublicationProduct lock(UUID id) {
        var p = products.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        entities.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        return snapshot(p);
    }
    @Transactional(readOnly = true)
    public PublicationProduct read(UUID id) {
        return snapshot(products.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND)));
    }
    @Transactional(readOnly = true)
    public Set<UUID> published(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of() : Set.copyOf(products.publishedIds(ids));
    }
    public void publish(UUID id) { publication.publish(id); }
    public void unpublish(UUID id) { publication.unpublish(id); }
    private PublicationProduct snapshot(com.stockflow.product.internal.entity.ProductJpaEntity p) {
        return new PublicationProduct(p.getId(), p.getName(), p.getDescription(), p.getCategoryId(),
                p.getStatus(), skus.findForProduct(p.getId()).stream()
                    .map(s -> new PublicationProduct.PublicationSku(s.getId(), s.getCode())).toList());
    }
}
