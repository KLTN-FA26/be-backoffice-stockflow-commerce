package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.internal.repository.CustomerSkuJpaRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Every customer's SKUs of one product, whoever owns them: what the product page shows before a blank is blocked - which
 * stops those SKUs from being ordered - or the product is discontinued, which retires them. Staff only: the
 * controller's permission keeps it from a customer (13 BR-02).
 *
 * <p>Its own class rather than a method of {@link CustomerSkuService}: no other module asks it, so it is not on
 * {@code CustomerSkus}, and a module test that stands a mock in for {@code CustomerSkus} replaces that bean - a reader
 * of the concrete service would leave the product module unable to start there.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductCustomerSkus {

    private final CustomerSkuJpaRepository customerSkus;
    private final VariantJpaRepository variants;
    private final ProductJpaRepository products;

    ProductCustomerSkus(CustomerSkuJpaRepository customerSkus, VariantJpaRepository variants,
                        ProductJpaRepository products) {
        this.customerSkus = customerSkus;
        this.variants = variants;
        this.products = products;
    }

    /**
     * Every customer's SKU drawn on {@code productId}, or on its catalogue variant {@code variantId} when given, a page
     * at a time by code.
     *
     * @throws BusinessException {@code PRODUCT_NOT_FOUND} for an unknown product; {@code VARIANT_NOT_FOUND} for a
     *         variant that is not one of the product's own - named, rather than answered with an empty page
     */
    public PageResponse<CustomerSku> drawnOn(UUID productId, UUID variantId, int page, int size) {
        if (!products.existsById(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product " + productId);
        }
        if (variantId != null && variants.findByIdAndProductId(variantId, productId).isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND,
                    "Variant %s is not a variant of product %s".formatted(variantId, productId));
        }
        var found = customerSkus.drawnOn(productId, variantId, Pages.of(page, size));
        return PageResponse.of(CustomerSkuService.views(found.getContent(), variants), found.getNumber(),
                found.getSize(), found.getTotalElements());
    }
}
