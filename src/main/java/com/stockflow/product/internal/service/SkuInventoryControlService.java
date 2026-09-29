package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SkuInventoryControlChanged;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.StockThresholdEvaluation;
import com.stockflow.product.internal.entity.SkuJpaEntity;
import com.stockflow.product.internal.repository.SkuJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.UUID;

@Service
@Transactional
public class SkuInventoryControlService {
    private final SkuJpaRepository skus;
    private final VariantJpaRepository variants;
    private final InventoryControlService inventory;
    private final EntityManager entities;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final com.stockflow.product.internal.repository.ProductJpaRepository products;
    public SkuInventoryControlService(SkuJpaRepository skus, VariantJpaRepository variants,
            InventoryControlService inventory, EntityManager entities, ApplicationEventPublisher events, Clock clock,
            com.stockflow.product.internal.repository.ProductJpaRepository products) {
        this.skus = skus; this.variants = variants; this.inventory = inventory;
        this.entities = entities; this.events = events; this.clock = clock;
        this.products = products;
    }
    public record View(UUID skuId, String sku, String unitOfMeasure, long version,
                       InventoryPolicy policy, StockThresholdEvaluation evaluation) {}
    @Transactional(readOnly = true)
    public View get(UUID productId, UUID skuId) { return view(requireSku(productId, skuId)); }
    @Auditable(action = AuditAction.UPDATE, resourceType = "sku-inventory-control", resourceId = "#skuId")
    public View update(UUID productId, UUID skuId, long version, InventoryPolicy policy) {
        var product = products.findById(productId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        entities.refresh(product, LockModeType.PESSIMISTIC_WRITE);
        if (product.getStatus() == com.stockflow.product.api.ProductStatus.DISCONTINUED
                || product.getStatus() == com.stockflow.product.api.ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var sku = requireSku(productId, skuId);
        entities.refresh(sku, LockModeType.PESSIMISTIC_WRITE);
        if (sku.getVersion() != version) throw new BusinessException(ErrorCode.CONFLICT);
        if (!new Sku(sku.getCode()).code().equals(sku.getCode()))
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        inventory.configure(new Sku(sku.getCode()), policy);
        sku.applyInventoryControl(policy);
        skus.flush();
        events.publishEvent(new SkuInventoryControlChanged(sku.getCode(), sku.getVersion(), clock.instant()));
        return view(sku);
    }
    private SkuJpaEntity requireSku(UUID productId, UUID skuId) {
        var sku = skus.findById(skuId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!variants.findById(sku.getVariantId()).map(v -> v.getProductId().equals(productId)).orElse(false))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return sku;
    }
    private View view(SkuJpaEntity sku) {
        return new View(sku.getId(), sku.getCode(), sku.getUnitOfMeasure(), sku.getVersion(),
                sku.inventoryControl(), inventory.evaluate(new Sku(sku.getCode())));
    }
}
