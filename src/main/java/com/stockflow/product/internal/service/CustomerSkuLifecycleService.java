package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.CustomerSkuStatus;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.CustomerSkuJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.repository.CustomerSkuJpaRepository;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Blocking, unblocking and retiring a customer's own SKU (kltn-docs 12 §4.6; {@link CustomerSkuStatus}). The SKU was a
 * variant row until V20270105000100 and moved through the variant endpoints; it is its own table now, so it has its own
 * moves, with the same permissions and the same history ({@code VARIANT_STATUS} rows of its product, naming the SKU).
 *
 * <p>Locks: the product row first, then the SKU's - the order every variant write takes, and the lock a quote or an
 * order holds {@code FOR SHARE} while it relies on a SKU being purchasable ({@code ProductService.lockedPurchasability}),
 * so a move either committed before that read and is seen, or waits until the quote or order has committed.</p>
 *
 * <p>Going on sale again ({@link #activate}) asks what issuing asked: the product approved or published and the
 * variant the design was drawn on ACTIVE - a SKU is not sold while its blank is not. Blocking and retiring ask nothing
 * but the move itself: taking goods off sale is never refused for the state of the catalogue.</p>
 */
@Service
@Transactional
public class CustomerSkuLifecycleService {

    private final CustomerSkuJpaRepository customerSkus;
    private final VariantJpaRepository variants;
    private final ProductJpaRepository products;
    private final ProductChangeLogRepository history;
    private final CustomerSkus reads;
    private final Clock clock;

    public CustomerSkuLifecycleService(CustomerSkuJpaRepository customerSkus, VariantJpaRepository variants,
                                       ProductJpaRepository products, ProductChangeLogRepository history,
                                       CustomerSkus reads, Clock clock) {
        this.customerSkus = customerSkus;
        this.variants = variants;
        this.products = products;
        this.history = history;
        this.reads = reads;
        this.clock = clock;
    }

    /** BLOCKED back to ACTIVE, while its product is on sale and its source variant ACTIVE. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "customer-sku", resourceId = "#id")
    public CustomerSku activate(UUID id) {
        CustomerSkuJpaEntity sku = lock(id);
        ProductJpaEntity product = products.findById(sku.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        boolean onSale = product.getStatus() == ProductStatus.APPROVED || product.getStatus() == ProductStatus.PUBLISHED;
        boolean sourceActive = variants.findById(sku.getVariantId())
                .map(v -> v.getStatus() == VariantStatus.ACTIVE).orElse(false);
        if (!onSale || !sourceActive) {
            throw new BusinessException(ErrorCode.INVALID_CUSTOMER_SKU_TRANSITION,
                    "Customer SKU %s goes on sale only while its product is on sale and its variant is active"
                            .formatted(sku.getSku()));
        }
        return move(sku, CustomerSkuStatus.ACTIVE);
    }

    /** Off sale for now: no new quote line takes it (01 BR-04); what is running goes on. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "customer-sku", resourceId = "#id")
    public CustomerSku block(UUID id) {
        return move(lock(id), CustomerSkuStatus.BLOCKED);
    }

    /** For good (01 §5.2): never quoted again; its stock is still shipped and adjusted away. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "customer-sku", resourceId = "#id")
    public CustomerSku obsolete(UUID id) {
        return move(lock(id), CustomerSkuStatus.OBSOLETE);
    }

    /**
     * The SKU under its product's lock, then its own. The product is found by a projection, so the SKU's entity is first
     * loaded by the locking read: loaded before it, the locking query would hand back the instance already in the
     * persistence context without reading the row again, and the status judged would be the one read before the lock.
     */
    private CustomerSkuJpaEntity lock(UUID id) {
        UUID productId = customerSkus.productIdOf(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SKU_NOT_FOUND,
                        "No customer SKU " + id));
        products.lockById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return customerSkus.lockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_SKU_NOT_FOUND,
                        "No customer SKU " + id));
    }

    private CustomerSku move(CustomerSkuJpaEntity sku, CustomerSkuStatus target) {
        CustomerSkuStatus from = sku.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_CUSTOMER_SKU_TRANSITION,
                    "Customer SKU %s cannot go from %s to %s".formatted(sku.getSku(), from, target));
        }
        sku.moveTo(target, clock.instant());
        customerSkus.saveAndFlush(sku);
        history.record(sku.getProductId(), ProductChangeType.VARIANT_STATUS, sku.getId(), from.name(), target.name(),
                null, List.of());
        return reads.findById(sku.getId()).orElseThrow();
    }
}
