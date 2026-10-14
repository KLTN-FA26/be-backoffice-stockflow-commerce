package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.OpenReferences;
import com.stockflow.product.api.ProductChange;
import com.stockflow.product.api.ProductDiscontinuation;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.Purchasability;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import com.stockflow.product.internal.repository.ProductSearchCriteria;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The only implementation of {@link ProductService}, and the module's transaction boundary.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class.</p>
 *
 * <p>Every write answers with {@link ProductSearchRepository#summary}, read after the write, rather
 * than with a summary built from the aggregate: the brand name and the primary category are read
 * from other tables, and the audit columns are set by the flush.</p>
 *
 * <p>Every change to the product also goes to its history ({@code product_change_log}) in the same
 * transaction. A move that an edit makes of its own ({@code suspend}, {@code discontinue}, {@code delete})
 * locks the product row first and checks the version the caller read, so two people acting on one
 * screen cannot both win.</p>
 *
 * <p>What other modules hold against the product's SKUs (open purchase order lines, order lines not yet
 * shipped) is asked of every {@link SkuReferenceProbe} Spring hands in. The product module cannot call
 * procurement or order itself: {@code catalog} depends on it and {@code order} on {@code catalog}, so the
 * call would be a cycle.</p>
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
    private final ProductMediaService media;
    private final ProductEventPublisher events;
    private final ProductChangeLogRepository history;
    private final ProductCommerceRepository commerce;
    private final InventoryService inventory;
    private final IdentityService identity;
    private final List<SkuReferenceProbe> probes;
    private final Clock clock;

    ProductServiceImpl(ProductRepository products, ProductSearchRepository search, ProductVariantService variants,
                       ProductMediaService media, ProductEventPublisher events, ProductChangeLogRepository history,
                       ProductCommerceRepository commerce, InventoryService inventory, IdentityService identity,
                       List<SkuReferenceProbe> probes, Clock clock) {
        this.products = products;
        this.search = search;
        this.variants = variants;
        this.media = media;
        this.events = events;
        this.history = history;
        this.commerce = commerce;
        this.inventory = inventory;
        this.identity = identity;
        this.probes = probes;
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
        history.record(product.id().value(), ProductChangeType.CREATED, null, null, ProductStatus.DRAFT.name(), null,
                List.of());
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

    /**
     * {@inheritDoc}
     *
     * <p>The row is locked and its version compared before anything else: the caller edited the product
     * as it was when it read it.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#command.productId()")
    public ProductSummary update(UpdateProductCommand command) {
        Product product = requireProductForUpdate(command.productId());
        product.requireVersion(command.version());
        requireReferences(command.brandId(), command.categoryId());
        List<String> changed = product.updateDetails(command.name(), command.nameEn(), command.brandId(),
                command.categoryId(), command.shortDescription(), command.description(), command.descriptionEn(),
                command.taxClass(), command.kind());
        products.save(product);
        if (!changed.isEmpty()) {
            history.record(command.productId(), ProductChangeType.UPDATED, null, null, null, null, changed);
        }
        return summaryOf(command.productId());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary submit(UUID productId, UUID submittedBy) {
        Product product = requireProduct(productId);
        ProductStatus from = product.status();
        product.submit(submittedBy, clock.instant());
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.SUBMITTED, null, from.name(), saved.status().name(), null, List.of());
        return saved;
    }

    /** Approval makes the product sellable, and its draft variants with it. */
    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "product", resourceId = "#productId")
    public ProductSummary approve(UUID productId, UUID approverId) {
        Product product = requireProduct(productId);
        ProductStatus from = product.status();
        product.approve(approverId, clock.instant());
        variants.activateDrafts(productId);
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.APPROVED, null, from.name(), saved.status().name(), null, List.of());
        return saved;
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "product", resourceId = "#productId")
    public ProductSummary reject(UUID productId, UUID approverId, String reason) {
        Product product = requireProduct(productId);
        ProductStatus from = product.status();
        product.reject(approverId, reason, clock.instant());
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.REJECTED, null, from.name(), saved.status().name(), reason,
                List.of());
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order: the product row is locked and its version checked; the aggregate takes the move (which
     * refuses a status that cannot be discontinued, or a blank reason, before anything else changes); what
     * the other modules still hold is read; every variant is made OBSOLETE; the history gets the variants'
     * moves and then the discontinuation, so it reads newest-first the way it happened. The warnings never
     * stop it. A purchase order created at the same moment is turned away by {@link #purchasability} once
     * this commits.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductDiscontinuation discontinue(UUID productId, String reason, long version) {
        Product product = requireProductForUpdate(productId);
        product.requireVersion(version);
        ProductStatus from = product.status();
        product.discontinue(reason, clock.instant());
        OpenReferences open = referencesOf(products.skusOf(product.id()));
        variants.obsoleteAll(productId, product.discontinueReason());
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.DISCONTINUED, null, from.name(), saved.status().name(),
                product.discontinueReason(), List.of());
        return new ProductDiscontinuation(saved, warningsOf(open), open);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary suspend(UUID productId, String reason, long version) {
        Product product = requireProductForUpdate(productId);
        product.requireVersion(version);
        ProductStatus from = product.status();
        product.suspend(reason, clock.instant());
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.SUSPENDED, null, from.name(), saved.status().name(),
                product.suspendReason(), List.of());
        return saved;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product", resourceId = "#productId")
    public ProductSummary resume(UUID productId, long version) {
        Product product = requireProductForUpdate(productId);
        product.requireVersion(version);
        ProductStatus from = product.status();
        product.resume(clock.instant());
        ProductSummary saved = saveAndPublish(product);
        history.record(productId, ProductChangeType.RESUMED, null, from.name(), saved.status().name(), null, List.of());
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Refused while a purchase order line or an order line that is still open points at one of its SKUs,
     * and then by the foreign keys for everything else (stock, movements, receipts, carts, designs, a
     * storefront listing): that is the last line of defence, and it is a 409 {@code PRODUCT_IN_USE}, never a
     * 500. The inventory item each SKU got when its variant was created is deleted with it; the stored
     * image files are removed only after the commit.</p>
     */
    @Override
    @Auditable(action = AuditAction.DELETE, resourceType = "product", resourceId = "#productId")
    public void deleteDraft(UUID productId, long version) {
        Product product = requireProductForUpdate(productId);
        product.requireVersion(version);
        product.requireDeletable();
        List<String> skus = products.skusOf(product.id());
        OpenReferences open = referencesOf(skus);
        if (!open.references().isEmpty()) {
            SkuReference first = open.references().getFirst();
            throw new BusinessException(ErrorCode.PRODUCT_IN_USE, "%s %s still refers to %s".formatted(
                    first.kind(), first.documentNumber(), first.sku()));
        }
        List<String> stored = media.storedKeysOf(productId);
        try {
            inventory.deleteUnusedItems(skus);
        } catch (DataIntegrityViolationException stillReferenced) {
            throw new BusinessException(ErrorCode.PRODUCT_IN_USE,
                    "A SKU of product %s still has stock, purchasing or order history".formatted(productId),
                    stillReferenced);
        }
        products.delete(product.id());
        media.deleteStoredAfterCommit(stored);
    }

    @Override
    @Transactional(readOnly = true)
    public OpenReferences openReferences(UUID productId) {
        requireProduct(productId);
        return referencesOf(products.skusOf(new ProductId(productId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Purchasability> purchasability(Collection<String> skus) {
        return commerce.purchasability(skus.stream().filter(java.util.Objects::nonNull)
                .map(sku -> sku.strip().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductChange> history(UUID productId, int page, int size) {
        requireProduct(productId);
        var rows = history.page(productId, Pages.of(page, size));
        Map<UUID, String> names = identity.displayNames(
                rows.getContent().stream().map(ProductChangeLogRepository.Entry::actorId).toList());
        return Pages.toResponse(rows.map(row -> new ProductChange(row.type().name(), row.type().subject().name(),
                row.subjectId(), row.fromStatus(), row.toStatus(), row.reason(), row.changedFields(), row.actorId(),
                row.actorId() == null ? "system" : names.getOrDefault(row.actorId(), "unknown"), row.occurredAt())));
    }

    /**
     * What each probe found, at most {@link OpenReferences#LIMIT} in all: taken from the probes in turn, so a
     * product with hundreds of purchase order lines still shows the order lines it has too. Each probe is
     * asked for one more than the limit to know whether the list was cut.
     */
    private OpenReferences referencesOf(Collection<String> skus) {
        if (skus.isEmpty()) {
            return new OpenReferences(List.of(), false);
        }
        List<List<SkuReference>> found = probes.stream()
                .map(probe -> probe.openReferences(skus, OpenReferences.LIMIT + 1))
                .filter(list -> !list.isEmpty()).toList();
        int total = found.stream().mapToInt(List::size).sum();
        List<SkuReference> sample = new ArrayList<>();
        for (int i = 0; sample.size() < OpenReferences.LIMIT && sample.size() < total; i++) {
            for (List<SkuReference> list : found) {
                if (i < list.size() && sample.size() < OpenReferences.LIMIT) {
                    sample.add(list.get(i));
                }
            }
        }
        return new OpenReferences(sample, total > OpenReferences.LIMIT);
    }

    /** One warning per kind of document that still refers to the product. */
    private static List<String> warningsOf(OpenReferences open) {
        Set<String> warnings = new LinkedHashSet<>();
        Stream.of(SkuReference.Kind.values()).forEach(kind -> {
            if (open.references().stream().anyMatch(r -> r.kind() == kind)) {
                warnings.add(kind == SkuReference.Kind.PURCHASE_ORDER_LINE
                        ? ProductDiscontinuation.OPEN_PURCHASE_ORDER_LINES
                        : ProductDiscontinuation.UNSHIPPED_ORDER_LINES);
            }
        });
        return List.copyOf(warnings);
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

    /** The product, locked: a move or a deletion serialises with every other writer of the same product. */
    private Product requireProductForUpdate(UUID productId) {
        return products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
    }

    private ProductSummary summaryOf(UUID productId) {
        return search.summary(productId).orElseThrow();
    }
}
