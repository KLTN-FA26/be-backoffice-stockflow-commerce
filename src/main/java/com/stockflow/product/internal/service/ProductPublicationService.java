package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.repository.ProductGalleryJpaRepository;
import com.stockflow.product.api.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Publishes the approved master only after a separately approved customer-facing gallery exists. */
@Service
@Transactional
public class ProductPublicationService {
    private final ProductRepository products;
    private final ProductGalleryJpaRepository galleries;

    public ProductPublicationService(ProductRepository products, ProductGalleryJpaRepository galleries) {
        this.products = products;
        this.galleries = galleries;
    }

    @Auditable(action = AuditAction.APPROVE, resourceType = "product", resourceId = "#productId")
    public void publish(UUID productId) {
        var product = products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.status() == ProductStatus.PUBLISHED) {
            return;
        }
        var gallery = galleries.findById(productId)
                .filter(value -> !value.getPublishedItems().isEmpty())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "Approve a non-empty product gallery before publishing the product"));
        if (gallery.getApprovedBy() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "The product gallery is not approved");
        }
        product.publish();
        products.save(product);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    public void unpublish(UUID productId) {
        var product = products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.status() == ProductStatus.APPROVED) {
            return;
        }
        product.unpublish();
        products.save(product);
    }
}
