package com.stockflow.product.internal.service;

import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.repository.ProductSearchCriteria;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditHistory;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link ProductService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 */
@Service
@Transactional
class ProductServiceImpl implements ProductService {

    @Override
    @Transactional(readOnly = true)
    public boolean containsSku(UUID productId, String sku) { return products.containsSku(productId, sku); }

    private static final SortWhitelist SORT =
            SortWhitelist.of("name", "code", "createdAt", "lastModifiedAt", "status")
                    .withDefault("lastModifiedAt", Sort.Direction.DESC);

    private final ProductRepository products;
    private final ProductSearchRepository search;
    private final ProductEventPublisher events;
    private final AuditHistory auditHistory;
    private final Clock clock;

    ProductServiceImpl(ProductRepository products, ProductSearchRepository search,
                       ProductEventPublisher events, AuditHistory auditHistory, Clock clock) {
        this.products = products;
        this.search = search;
        this.events = events;
        this.auditHistory = auditHistory;
        this.clock = clock;
    }

    // No @Auditable here: the id is generated inside Product.draft(), so there is no method
    // argument to bind resourceId to - same reason createPurchaseOrder/placeOrder are not audited
    // either. update() below is the first point the id exists as an argument.
    @Override
    public ProductSummary create(CreateProductCommand command) {
        if (command.categoryId() != null && !products.categoryExists(command.categoryId())) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                    "No category with id " + command.categoryId());
        }
        if (products.existsByCode(command.code())) {
            // Proactive check for a clean message; uk_product_code is the real guard against a
            // race, the same "check then insert loses a race, the database does not" reasoning as
            // StockItemJpaEntity.
            throw new BusinessException(ErrorCode.PRODUCT_CODE_ALREADY_EXISTS,
                    "A product with code " + command.code() + " already exists");
        }
        Product product = Product.draft(command.code(), command.name(), command.nameEn(),
                command.categoryId(), command.description(), command.descriptionEn(),
                command.brand(), command.taxClass(), command.customizable(),
                toImages(command.images()), command.weightKg(), command.lengthCm(),
                command.widthCm(), command.heightCm(), command.packageWeightKg(),
                command.packageLengthCm(), command.packageWidthCm(), command.packageHeightCm(),
                command.packageCount(), command.hazmat(), command.oversized(),
                command.requiresAdultSignature(), command.shippingRestrictionNote());
        return toSummary(products.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> findById(UUID productId) {
        return products.findById(new ProductId(productId)).map(ProductServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> list(ListProductsQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        var criteria = new ProductSearchCriteria(query.search(), query.statuses());
        return Pages.toResponse(search.search(criteria, pageable));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#command.productId()")
    public ProductSummary update(UpdateProductCommand command) {
        Product product = requireProduct(command.productId());
        if (command.categoryId() != null && !products.categoryExists(command.categoryId())) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                    "No category with id " + command.categoryId());
        }
        // Legacy URL editing must never discard attachments uploaded through the media endpoint.
        var images = new java.util.ArrayList<ProductImage>();
        product.images().stream().filter(image -> image.storedFile() != null).forEach(images::add);
        int nextOrder = images.stream().mapToInt(ProductImage::sortOrder).max().orElse(-1) + 1;
        for (var legacy : toImages(command.images())) {
            images.add(new ProductImage(legacy.id(), legacy.url(), nextOrder++));
        }
        product.updateDetails(command.name(), command.nameEn(), command.categoryId(),
                command.description(), command.descriptionEn(), command.brand(),
                command.taxClass(), command.customizable(), images,
                command.weightKg(), command.lengthCm(), command.widthCm(), command.heightCm(),
                command.packageWeightKg(), command.packageLengthCm(), command.packageWidthCm(),
                command.packageHeightCm(), command.packageCount(), command.hazmat(),
                command.oversized(), command.requiresAdultSignature(),
                command.shippingRestrictionNote());
        return toSummary(products.save(product));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary submit(UUID productId, UUID submittedBy) {
        Product product = requireProduct(productId);
        product.submit(submittedBy, clock.instant());
        Product saved = products.save(product);
        events.publishEventsOf(saved);
        return toSummary(saved);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "product", resourceId = "#productId")
    public ProductSummary approve(UUID productId, UUID approverId) {
        Product product = requireProduct(productId);
        product.approve(approverId, clock.instant());
        Product saved = products.save(product);
        events.publishEventsOf(saved);
        return toSummary(saved);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "product", resourceId = "#productId")
    public ProductSummary reject(UUID productId, UUID approverId, String reason) {
        Product product = requireProduct(productId);
        product.reject(approverId, reason, clock.instant());
        Product saved = products.save(product);
        events.publishEventsOf(saved);
        return toSummary(saved);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary discontinue(UUID productId) {
        Product product = requireProduct(productId);
        product.discontinue(clock.instant());
        Product saved = products.save(product);
        events.publishEventsOf(saved);
        return toSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> history(UUID productId, int page, int size) {
        requireProduct(productId);
        return auditHistory.forResource("product", productId.toString(), page, size);
    }

    private Product requireProduct(UUID productId) {
        return products.findById(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "No product with id " + productId));
    }

    private static List<ProductImage> toImages(List<String> urls) {
        if (urls == null) {
            return List.of();
        }
        List<ProductImage> images = new java.util.ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            images.add(new ProductImage(Identifiers.newId(), urls.get(i), i));
        }
        return images;
    }

    private static ProductSummary toSummary(Product product) {
        return new ProductSummary(
                product.id().value(),
                product.code(),
                product.name(),
                product.nameEn(),
                product.categoryId(),
                product.description(),
                product.descriptionEn(),
                product.brand(),
                product.taxClass(),
                product.customizable(),
                // Uploaded media is available through the guarded /images endpoints. Signed URLs
                // must not enter the cross-module contract or persisted/cached product summaries.
                product.images().stream().filter(image -> image.storedFile() == null)
                        .map(ProductImage::url).toList(),
                product.status(),
                product.createdAt(),
                product.createdBy(),
                product.lastModifiedAt(),
                product.lastModifiedBy(),
                product.submittedBy(),
                product.submittedAt(),
                product.approvedBy(),
                product.approvedAt(),
                product.rejectionReason(),
                product.weightKg(),
                product.lengthCm(),
                product.widthCm(),
                product.heightCm(),
                product.packageWeightKg(),
                product.packageLengthCm(),
                product.packageWidthCm(),
                product.packageHeightCm(),
                product.packageCount(),
                product.hazmat(),
                product.oversized(),
                product.requiresAdultSignature(),
                product.shippingRestrictionNote());
    }
}
