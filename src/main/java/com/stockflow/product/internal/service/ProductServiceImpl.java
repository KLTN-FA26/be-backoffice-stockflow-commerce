package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditHistory;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.repository.ProductSearchCriteria;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link ProductService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 *
 * <p>Every write answers with {@link ProductSearchRepository#summary}, read after the write, rather
 * than with a summary built from the aggregate: the brand name and the primary category are read
 * from other tables, and the audit columns are set by the flush.</p>
 */
@Service
@Transactional
class ProductServiceImpl implements ProductService {

    private static final SortWhitelist SORT =
            SortWhitelist.of("name", "code", "createdAt", "lastModifiedAt", "status")
                    .withDefault("lastModifiedAt", Sort.Direction.DESC);

    private final ProductRepository products;
    private final ProductSearchRepository search;
    private final ProductVariantService variants;
    private final ProductEventPublisher events;
    private final AuditHistory auditHistory;
    private final Clock clock;

    ProductServiceImpl(ProductRepository products, ProductSearchRepository search, ProductVariantService variants,
                       ProductEventPublisher events, AuditHistory auditHistory, Clock clock) {
        this.products = products;
        this.search = search;
        this.variants = variants;
        this.events = events;
        this.auditHistory = auditHistory;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean containsSku(UUID productId, String sku) {
        return products.containsSku(productId, sku);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> nameForSku(String sku) {
        return products.nameForSku(sku);
    }

    /**
     * A draft product and its default variant, whose SKU is the product code — the variant creates
     * the SKU's inventory item (trigger), where its logistics are then filled in. Both codes are
     * checked first for a clean 409; {@code uk_products_code} and {@code uk_variants_sku} are the
     * real guards against a race.
     */
    // The id is generated inside Product.draft(), so it is read off the result: the CREATE entry
    // is the first row of the product's version history (SCRUM-86).
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "product", resourceId = "#result?.productId()")
    public ProductSummary create(CreateProductCommand command) {
        requireReferences(command.brandId(), command.categoryId());
        if (products.existsByCode(command.code())) {
            throw new BusinessException(ErrorCode.PRODUCT_CODE_ALREADY_EXISTS,
                    "A product with code " + command.code() + " already exists");
        }
        if (products.skuExists(command.code())) {
            throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                    "SKU " + command.code() + " already belongs to another product's variant");
        }
        Product product = Product.draft(command.code(), command.name(), command.nameEn(), command.brandId(),
                command.categoryId(), command.shortDescription(), command.description(), command.descriptionEn(),
                command.taxClass(), command.kind());
        products.save(product);
        variants.createDefault(product.id().value(), product.code(), product.name());
        return summaryOf(product.id().value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> findById(UUID productId) {
        return search.summary(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> list(ListProductsQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        var criteria = new ProductSearchCriteria(query.search(), query.statuses(), query.categoryId(), query.brandId());
        return Pages.toResponse(search.search(criteria, pageable));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#command.productId()")
    public ProductSummary update(UpdateProductCommand command) {
        Product product = requireProduct(command.productId());
        requireReferences(command.brandId(), command.categoryId());
        product.updateDetails(command.name(), command.nameEn(), command.brandId(), command.categoryId(),
                command.shortDescription(), command.description(), command.descriptionEn(), command.taxClass(),
                command.kind());
        products.save(product);
        return summaryOf(command.productId());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary submit(UUID productId, UUID submittedBy) {
        Product product = requireProduct(productId);
        product.submit(submittedBy, clock.instant());
        return saveAndPublish(product);
    }

    /** Approval makes the product sellable, and its draft variants with it. */
    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "product", resourceId = "#productId")
    public ProductSummary approve(UUID productId, UUID approverId) {
        Product product = requireProduct(productId);
        product.approve(approverId, clock.instant());
        variants.activateDrafts(productId);
        return saveAndPublish(product);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "product", resourceId = "#productId")
    public ProductSummary reject(UUID productId, UUID approverId, String reason) {
        Product product = requireProduct(productId);
        product.reject(approverId, reason, clock.instant());
        return saveAndPublish(product);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary discontinue(UUID productId) {
        Product product = requireProduct(productId);
        product.discontinue(clock.instant());
        return saveAndPublish(product);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> history(UUID productId, int page, int size) {
        requireProduct(productId);
        return auditHistory.forResource("product", productId.toString(), page, size);
    }

    /** Events are drained from the instance that recorded them: {@code save} returns a fresh one. */
    private ProductSummary saveAndPublish(Product product) {
        products.save(product);
        events.publishEventsOf(product);
        return summaryOf(product.id().value());
    }

    private void requireReferences(UUID brandId, UUID categoryId) {
        if (brandId != null && !products.brandExists(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND, "No brand with id " + brandId);
        }
        if (categoryId != null && !products.categoryExists(categoryId)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "No category with id " + categoryId);
        }
    }

    private Product requireProduct(UUID productId) {
        return products.findById(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
    }

    private ProductSummary summaryOf(UUID productId) {
        return search.summary(productId).orElseThrow();
    }
}
