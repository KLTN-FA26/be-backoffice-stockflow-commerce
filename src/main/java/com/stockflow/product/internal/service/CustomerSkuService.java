package com.stockflow.product.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.ItemSpecifications;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.IssueCustomerSkuCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import jakarta.persistence.EntityManager;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Issues and reads customers' own SKUs (kltn-docs 12 §4.5, 01 §4.8, glossary, B20).
 *
 * <p>Issuing runs in the caller's transaction - the design approval's - under the product lock, the same lock every
 * variant write of the product takes: two approvals for one customer on one product number their SKUs one after the
 * other, and a variant blocked meanwhile is seen. One SKU per snapshot is stated twice: looked up first, so a retried
 * approval returns the SKU it issued, and {@code uk_variants_design_snapshot} in the database.</p>
 *
 * <p>The code is "product - customer - number" ({@code CUP12-0312345678-01}); a long product code shortens the
 * customer's part rather than failing the approval, and the number moves on past a code already taken, so two
 * customers whose codes shorten alike never meet.</p>
 */
@Service
public class CustomerSkuService implements CustomerSkus {

    private static final Pattern CUSTOMER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,19}");
    private static final int MAX_SKU = 64;

    private final VariantJpaRepository variants;
    private final ProductJpaRepository products;
    private final ItemSpecifications specifications;
    private final ProductChangeLogRepository history;
    private final EntityManager entityManager;

    public CustomerSkuService(VariantJpaRepository variants, ProductJpaRepository products,
                              ItemSpecifications specifications, ProductChangeLogRepository history,
                              EntityManager entityManager) {
        this.variants = variants;
        this.products = products;
        this.specifications = specifications;
        this.history = history;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public CustomerSku issue(IssueCustomerSkuCommand command) {
        if (command.designSnapshotId() == null || command.customerId() == null || command.sourceSku() == null
                || command.customerCode() == null || !CUSTOMER_CODE.matcher(command.customerCode()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A customer SKU needs a snapshot, a customer, a customer code and a source variant");
        }
        VariantJpaEntity source = variants.findBySku(command.sourceSku())
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));
        ProductJpaEntity product = products.lockById(source.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        // Read before the lock to find the product; read again under it, so a block committed meanwhile is seen.
        entityManager.refresh(source);

        Optional<VariantJpaEntity> issued = variants.findByDesignSnapshotId(command.designSnapshotId());
        if (issued.isPresent()) {
            VariantJpaEntity earlier = issued.get();
            if (!command.customerId().equals(earlier.getOwnerCustomerId())
                    || !source.getId().equals(earlier.getSourceVariantId())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Design snapshot %s already has the SKU %s".formatted(command.designSnapshotId(),
                                earlier.getSku()));
            }
            return view(earlier, source.getSku());
        }

        if (product.getKind() != ProductKind.CUSTOMIZABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_CUSTOMIZABLE,
                    "Product %s is not customizable".formatted(product.getCode()));
        }
        if ((product.getStatus() != ProductStatus.APPROVED && product.getStatus() != ProductStatus.PUBLISHED)
                || source.getStatus() != VariantStatus.ACTIVE || source.getOwnerCustomerId() != null) {
            throw new BusinessException(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE,
                    "Variant %s of product %s cannot have customer SKUs now".formatted(source.getSku(),
                            product.getCode()));
        }

        String sku = nextCode(product, command.customerId(), command.customerCode());
        String name = command.name() == null || command.name().isBlank() ? source.getName()
                : command.name().trim();
        var variant = new VariantJpaEntity(Identifiers.newId(), product.getId(), sku,
                name.length() > 255 ? name.substring(0, 255) : name, "OWNED-SKU:" + sku,
                variants.nextPosition(product.getId()), false);
        variant.ownBy(command.customerId(), command.designSnapshotId(), source.getId());
        variants.saveAndFlush(variant);
        history.record(product.getId(), ProductChangeType.VARIANT_ADDED, variant.getId(), null,
                variant.getStatus().name(), "customer SKU of design snapshot " + command.designSnapshotId(), List.of());
        // The insert created the SKU's item (tg_variant_inventory_item); it now takes the source's specification.
        specifications.copy(new Sku(source.getSku()), new Sku(sku));
        return view(variant, source.getSku());
    }

    @Override
    @Transactional
    public String nextSku(String sourceSku, UUID customerId, String customerCode) {
        if (sourceSku == null || customerId == null || customerCode == null
                || !CUSTOMER_CODE.matcher(customerCode).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A customer SKU needs a customer, a customer code and a source variant");
        }
        VariantJpaEntity source = variants.findBySku(sourceSku)
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));
        ProductJpaEntity product = products.lockById(source.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return nextCode(product, customerId, customerCode);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireVisibleTo(java.util.Collection<String> skus, UUID customerId) {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        List<String> upper = skus.stream().filter(java.util.Objects::nonNull)
                .map(s -> s.strip().toUpperCase(java.util.Locale.ROOT)).distinct().toList();
        if (upper.isEmpty()) {
            return;
        }
        UUID viewer = customerId == null ? new UUID(0, 0) : customerId;
        if (!variants.ownedByOthers(upper, viewer).isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> ownerOf(String sku) {
        return variants.findBySku(sku).map(VariantJpaEntity::getOwnerCustomerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> find(String sku) {
        // The source quy cách is not read: a production order needs the design, not what it was drawn on.
        return variants.findBySku(sku).filter(v -> v.getOwnerCustomerId() != null).map(v -> view(v, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> findByVariant(UUID variantId) {
        return variants.findById(variantId).filter(v -> v.getOwnerCustomerId() != null).map(v -> view(v, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UUID> ownersOf(java.util.Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        return variants.findBySkuIn(skus).stream().filter(v -> v.getOwnerCustomerId() != null)
                .collect(Collectors.toMap(VariantJpaEntity::getSku, VariantJpaEntity::getOwnerCustomerId));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> activeOwnedBy(UUID customerId, java.util.Collection<String> skus) {
        if (customerId == null || skus == null || skus.isEmpty()) {
            return java.util.Set.of();
        }
        return variants.findBySkuIn(skus).stream()
                .filter(v -> customerId.equals(v.getOwnerCustomerId())
                        && v.getStatus() == com.stockflow.product.internal.domain.VariantStatus.ACTIVE)
                .map(VariantJpaEntity::getSku)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSku> ownedBy(UUID customerId, UUID productId) {
        List<VariantJpaEntity> owned = variants.ownedBy(customerId, productId);
        Map<UUID, String> sources = variants.findAllById(owned.stream().map(VariantJpaEntity::getSourceVariantId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(VariantJpaEntity::getId, VariantJpaEntity::getSku));
        return owned.stream().map(v -> view(v, sources.get(v.getSourceVariantId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> ofSnapshot(UUID designSnapshotId) {
        return variants.findByDesignSnapshotId(designSnapshotId).map(v -> view(v,
                v.getSourceVariantId() == null ? null
                        : variants.findById(v.getSourceVariantId()).map(VariantJpaEntity::getSku).orElse(null)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CustomerSku> ofSnapshots(java.util.Collection<UUID> designSnapshotIds) {
        if (designSnapshotIds == null || designSnapshotIds.isEmpty()) {
            return Map.of();
        }
        List<VariantJpaEntity> issued = variants.findByDesignSnapshotIdIn(designSnapshotIds);
        Map<UUID, String> sources = variants.findAllById(issued.stream().map(VariantJpaEntity::getSourceVariantId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(VariantJpaEntity::getId, VariantJpaEntity::getSku));
        return issued.stream().collect(Collectors.toMap(VariantJpaEntity::getDesignSnapshotId,
                v -> view(v, sources.get(v.getSourceVariantId()))));
    }

    /** The first free "product - customer - number" code, the customer's part shortened to fit 64 characters. */
    private String nextCode(ProductJpaEntity product, UUID customerId, String customerCode) {
        long n = variants.countByProductIdAndOwnerCustomerId(product.getId(), customerId);
        while (true) {
            n++;
            String number = n < 10 ? "0" + n : Long.toString(n);
            int room = MAX_SKU - product.getCode().length() - number.length() - 2;
            if (room < 1) {
                throw new BusinessException(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE,
                        "Product code %s is too long for a customer SKU".formatted(product.getCode()));
            }
            String customer = customerCode.length() > room ? customerCode.substring(0, room) : customerCode;
            while (customer.length() > 1 && customer.endsWith("-")) {
                customer = customer.substring(0, customer.length() - 1);
            }
            String candidate = product.getCode().toUpperCase(java.util.Locale.ROOT) + "-" + customer + "-" + number;
            try {
                new Sku(candidate);
            } catch (IllegalArgumentException malformed) {
                throw new BusinessException(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE,
                        "Product code %s cannot start a SKU".formatted(product.getCode()));
            }
            if (!variants.existsBySku(candidate)) {
                return candidate;
            }
        }
    }

    private static CustomerSku view(VariantJpaEntity v, String sourceSku) {
        return new CustomerSku(v.getId(), v.getProductId(), v.getSku(), v.getName(), v.getOwnerCustomerId(),
                v.getDesignSnapshotId(), sourceSku, v.getStatus().name());
    }
}
