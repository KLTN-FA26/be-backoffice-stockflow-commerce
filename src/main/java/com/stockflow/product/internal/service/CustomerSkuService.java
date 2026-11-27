package com.stockflow.product.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.IssueCustomerSkuCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.CustomerSkuStatus;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.CustomerSkuJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.CustomerSkuJpaRepository;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Issues and reads customers' own SKUs (kltn-docs 12 §4.5, 01 §4.8, glossary), rows of {@code product.customer_skus}
 * since V20270105000100 (owner decision Q4 of 2026-11-26).
 *
 * <p>Issuing runs in the caller's transaction - the design approval's - under the product lock, the same lock every
 * variant write of the product takes: two approvals for one customer on one product number their SKUs one after the
 * other, and a variant blocked meanwhile is seen. One SKU per snapshot is stated twice: looked up first, so a retried
 * approval returns the SKU it issued, and {@code uk_customer_skus_design_snapshot} in the database.</p>
 *
 * <p>The code is "product - customer - number" ({@code CUP12-0312345678-01}); a long product code shortens the
 * customer's part rather than failing the approval, and the number moves on past a code already taken - by another
 * customer's SKU or by a catalogue variant ({@code tg_customer_skus_code_free} refuses that last) - so two customers
 * whose codes shorten alike never meet.</p>
 *
 * <p>Nothing is copied onto the new SKU and nothing is made in inventory: it is packed in its source variant's carton
 * (owner decision Q1), and its inventory item is made when an order first names it (Q5,
 * {@code InventoryService.openItems}).</p>
 */
@Service
public class CustomerSkuService implements CustomerSkus {

    private static final Pattern CUSTOMER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,19}");
    private static final int MAX_SKU = 64;

    private final CustomerSkuJpaRepository customerSkus;
    private final VariantJpaRepository variants;
    private final ProductJpaRepository products;
    private final ProductChangeLogRepository history;
    private final EntityManager entityManager;

    public CustomerSkuService(CustomerSkuJpaRepository customerSkus, VariantJpaRepository variants,
                              ProductJpaRepository products, ProductChangeLogRepository history,
                              EntityManager entityManager) {
        this.customerSkus = customerSkus;
        this.variants = variants;
        this.products = products;
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

        Optional<CustomerSkuJpaEntity> issued = customerSkus.findByDesignSnapshotId(command.designSnapshotId());
        if (issued.isPresent()) {
            CustomerSkuJpaEntity earlier = issued.get();
            if (!command.customerId().equals(earlier.getCustomerId())
                    || !source.getId().equals(earlier.getVariantId())) {
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
                || source.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE,
                    "Variant %s of product %s cannot have customer SKUs now".formatted(source.getSku(),
                            product.getCode()));
        }

        String sku = nextCode(product, command.customerId(), command.customerCode());
        String name = command.name() == null || command.name().isBlank() ? source.getName()
                : command.name().trim();
        var created = new CustomerSkuJpaEntity(Identifiers.newId(), product.getId(), source.getId(),
                command.customerId(), command.designSnapshotId(), sku,
                name.length() > 255 ? name.substring(0, 255) : name);
        customerSkus.saveAndFlush(created);
        history.record(product.getId(), ProductChangeType.VARIANT_ADDED, created.getId(), null,
                created.getStatus().name(), "customer SKU of design snapshot " + command.designSnapshotId(), List.of());
        return view(created, source.getSku());
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
    public void requireVisibleTo(Collection<String> skus, UUID customerId) {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        List<String> upper = skus.stream().filter(Objects::nonNull)
                .map(s -> s.strip().toUpperCase(Locale.ROOT)).distinct().toList();
        if (upper.isEmpty()) {
            return;
        }
        UUID viewer = customerId == null ? new UUID(0, 0) : customerId;
        if (!customerSkus.ownedByOthers(upper, viewer).isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> ownerOf(String sku) {
        return customerSkus.findBySku(sku).map(CustomerSkuJpaEntity::getCustomerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> find(String sku) {
        return customerSkus.findBySku(sku).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> findById(UUID id) {
        return id == null ? Optional.empty() : customerSkus.findById(id).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, CustomerSku> findAll(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        return views(customerSkus.findBySkuIn(skus)).stream()
                .collect(Collectors.toMap(CustomerSku::sku, v -> v));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> sourceSkusOf(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        return views(customerSkus.findBySkuIn(skus)).stream()
                .filter(v -> v.sourceSku() != null)
                .collect(Collectors.toMap(CustomerSku::sku, CustomerSku::sourceSku));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UUID> ownersOf(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        return customerSkus.findBySkuIn(skus).stream()
                .collect(Collectors.toMap(CustomerSkuJpaEntity::getSku, CustomerSkuJpaEntity::getCustomerId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> activeOwnedBy(UUID customerId, Collection<String> skus) {
        if (customerId == null || skus == null || skus.isEmpty()) {
            return Set.of();
        }
        return customerSkus.findBySkuIn(skus).stream()
                .filter(c -> customerId.equals(c.getCustomerId()) && c.getStatus() == CustomerSkuStatus.ACTIVE)
                .map(CustomerSkuJpaEntity::getSku)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSku> ownedBy(UUID customerId, UUID productId) {
        return views(customerSkus.ownedBy(customerId, productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DrawnSkus> drawnOn(String sourceSku, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100, not " + limit);
        }
        if (sourceSku == null || sourceSku.isBlank()) {
            return Optional.empty();
        }
        return variants.findBySku(sourceSku.strip().toUpperCase(Locale.ROOT)).map(variant -> new DrawnSkus(
                views(customerSkus.findByVariantIdOrderBySkuAscIdAsc(variant.getId(),
                        org.springframework.data.domain.PageRequest.of(0, limit))),
                customerSkus.countByVariantId(variant.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSku> ofSnapshot(UUID designSnapshotId) {
        return customerSkus.findByDesignSnapshotId(designSnapshotId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CustomerSku> ofSnapshots(Collection<UUID> designSnapshotIds) {
        if (designSnapshotIds == null || designSnapshotIds.isEmpty()) {
            return Map.of();
        }
        return views(customerSkus.findByDesignSnapshotIdIn(designSnapshotIds)).stream()
                .collect(Collectors.toMap(CustomerSku::designSnapshotId, v -> v));
    }

    /** The first free "product - customer - number" code, the customer's part shortened to fit 64 characters. */
    private String nextCode(ProductJpaEntity product, UUID customerId, String customerCode) {
        long n = customerSkus.countByProductIdAndCustomerId(product.getId(), customerId);
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
            String candidate = product.getCode().toUpperCase(Locale.ROOT) + "-" + customer + "-" + number;
            try {
                new Sku(candidate);
            } catch (IllegalArgumentException malformed) {
                throw new BusinessException(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE,
                        "Product code %s cannot start a SKU".formatted(product.getCode()));
            }
            // A code is one thing's: neither another customer's SKU nor a catalogue variant may hold it.
            if (!customerSkus.existsBySku(candidate) && !variants.existsBySku(candidate)) {
                return candidate;
            }
        }
    }

    /** One SKU with its source variant's code (one more read). */
    private CustomerSku view(CustomerSkuJpaEntity c) {
        return view(c, variants.findById(c.getVariantId()).map(VariantJpaEntity::getSku).orElse(null));
    }

    /** Many SKUs with their source variants' codes, in one more read for them all. */
    private List<CustomerSku> views(List<CustomerSkuJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> sources = new HashMap<>();
        variants.findAllById(found.stream().map(CustomerSkuJpaEntity::getVariantId).distinct().toList())
                .forEach(v -> sources.put(v.getId(), v.getSku()));
        return found.stream().map(c -> view(c, sources.get(c.getVariantId()))).toList();
    }

    private static CustomerSku view(CustomerSkuJpaEntity c, String sourceSku) {
        return new CustomerSku(c.getId(), c.getProductId(), c.getSku(), c.getName(), c.getCustomerId(),
                c.getDesignSnapshotId(), c.getVariantId(), sourceSku, c.getStatus().name());
    }
}
