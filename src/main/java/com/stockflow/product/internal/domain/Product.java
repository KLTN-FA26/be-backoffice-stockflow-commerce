package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.contracts.ProductApproved;
import com.stockflow.contracts.ProductDiscontinued;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>Aggregate root of the product module: one product master row</b> ({@code product.products}).
 *
 * <p>SCRUM-56 (WBS 3.1.1.2) landed the master-data fields; SCRUM-57 (WBS 3.1.1.3) adds the
 * approval workflow on top: {@code submit()}/{@code approve()}/{@code reject()}. SCRUM-85
 * (WBS 3.1.8.1) adds {@code discontinue()}, the terminal edge — all following
 * {@link ProductStatus#canTransitionTo}'s table. {@link #updateDetails} guards on {@code DRAFT}.</p>
 *
 * <p>What is deliberately <b>not</b> on the product (the PIM model, docs 01): logistics — weight,
 * dimensions, package, storage class — belong to the inventory item of each SKU (BR-08), and images
 * belong to a variant (decision D5). The product's selling SKUs are its variants; the first one is
 * created with the product, its SKU equal to the product code. Publication (status
 * {@code PUBLISHED}) is decided by the catalog through {@code ProductPublication}, never here.</p>
 *
 * <p>No Spring, no JPA, no annotations — {@code ArchitectureTest.domainDoesNotDependOnFrameworks}
 * enforces it. {@code code} is immutable once created, as the {@code tg_products_immutable} trigger
 * states again. Transition guards throw {@link InvalidProductStatusTransitionException}/
 * {@link SelfApprovalNotAllowedException} (dedicated {@code BusinessException}s), never
 * {@code IllegalStateException}.</p>
 */
public final class Product extends AggregateRoot {

    /** {@code ck_products_code}, stated again. Also a valid variant SKU, which the default variant needs. */
    public static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");

    private final ProductId id;
    private final String code;

    private String name;
    private String nameEn;
    private UUID brandId;
    private UUID categoryId;
    private String shortDescription;
    private String description;
    private String descriptionEn;
    private TaxClass taxClass;
    private ProductKind kind;
    private ProductStatus status;
    private final long version;

    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private String rejectionReason;
    private Instant discontinuedAt;

    public Product(ProductId id, String code, String name, String nameEn, UUID brandId, UUID categoryId,
                   String shortDescription, String description, String descriptionEn, TaxClass taxClass,
                   ProductKind kind, ProductStatus status, long version,
                   UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                   String rejectionReason, Instant discontinuedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = requireCode(code);
        this.name = requireNonBlank(name, "name");
        this.nameEn = nameEn;
        this.brandId = brandId;
        this.categoryId = categoryId;
        this.shortDescription = shortDescription;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.taxClass = taxClass;
        this.kind = Objects.requireNonNullElse(kind, ProductKind.STANDARD);
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.rejectionReason = rejectionReason;
        this.discontinuedAt = discontinuedAt;
    }

    /** A new product master row, always born {@link ProductStatus#DRAFT}. */
    public static Product draft(String code, String name, String nameEn, UUID brandId, UUID categoryId,
                                String shortDescription, String description, String descriptionEn,
                                TaxClass taxClass, ProductKind kind) {
        return new Product(ProductId.newId(), code, name, nameEn, brandId, categoryId, shortDescription,
                description, descriptionEn, taxClass, kind, ProductStatus.DRAFT, 0L,
                null, null, null, null, null, null);
    }

    /**
     * Replace every editable field. {@code code} is not a parameter: it is fixed at creation.
     *
     * <p>{@code DRAFT}-only: no business rule defines what an edit means once a product is
     * {@code PENDING_APPROVAL} or {@code APPROVED} — refusing outright is the honest choice until
     * product/PM decide whether an edit should reset the workflow or is allowed to pass through.
     * The selling content (slug, SEO) is edited through the catalog and is not affected.</p>
     */
    public void updateDetails(String name, String nameEn, UUID brandId, UUID categoryId, String shortDescription,
                              String description, String descriptionEn, TaxClass taxClass, ProductKind kind) {
        requireStatus(ProductStatus.DRAFT, "edited");
        this.name = requireNonBlank(name, "name");
        this.nameEn = nameEn;
        this.brandId = brandId;
        this.categoryId = categoryId;
        this.shortDescription = shortDescription;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.taxClass = taxClass;
        this.kind = Objects.requireNonNullElse(kind, ProductKind.STANDARD);
    }

    /**
     * Submit for approval. BR-PRD-001's fuller precondition (UoM, dimensions) is about the SKUs'
     * inventory items now, not the product; what the product itself must have is a category. The
     * product always has at least one variant — it is created with one.
     */
    public void submit(UUID submittedBy, Instant now) {
        requireCanTransitionTo(ProductStatus.PENDING_APPROVAL);
        if (categoryId == null) {
            throw new InvalidProductStatusTransitionException(id,
                    "cannot be submitted for approval: a category is required (BR-PRD-001, minimal subset)");
        }
        this.status = ProductStatus.PENDING_APPROVAL;
        this.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.rejectionReason = null;
    }

    /** BR-PRD-003: the approver must not be the person who submitted it. */
    public void approve(UUID approverId, Instant now) {
        requireCanTransitionTo(ProductStatus.APPROVED);
        Objects.requireNonNull(approverId, "approverId");
        if (submittedBy != null && submittedBy.equals(approverId)) {
            throw new SelfApprovalNotAllowedException(id, approverId);
        }
        this.status = ProductStatus.APPROVED;
        this.approvedBy = approverId;
        this.approvedAt = Objects.requireNonNull(now, "now");
        registerEvent(new ProductEvent.Approved(new ProductApproved(id.value(), approverId, submittedBy)));
    }

    /** Back to {@code DRAFT}; clears the submission so a resubmit starts clean. */
    public void reject(UUID approverId, String reason, Instant now) {
        requireCanTransitionTo(ProductStatus.DRAFT);
        Objects.requireNonNull(approverId, "approverId");
        this.status = ProductStatus.DRAFT;
        this.rejectionReason = reason;
        this.submittedBy = null;
        this.submittedAt = null;
    }

    /**
     * Terminal (WBS 3.1.8.1). Valid from {@code APPROVED} or {@code PUBLISHED} per
     * {@link ProductStatus#canTransitionTo}. {@code discontinuedAt} is what
     * {@code ck_products_discontinued} requires of a discontinued row.
     *
     * <p><b>BR-PRD-005 is not enforced here</b> ("no open PO and no unshipped order line
     * references it"): checking it needs cross-module reads of procurement and order, which belong
     * in an application service, not in a silent no-op invariant here — flagged rather than faked.</p>
     */
    public void discontinue(Instant now) {
        requireCanTransitionTo(ProductStatus.DISCONTINUED);
        this.status = ProductStatus.DISCONTINUED;
        this.discontinuedAt = Objects.requireNonNull(now, "now");
        registerEvent(new ProductEvent.Discontinued(new ProductDiscontinued(id.value(), now)));
    }

    /** Whether the product's media and variants may be changed now: not while it is under review,
     *  and never once it is discontinued. */
    public boolean acceptsChanges() {
        return status != ProductStatus.PENDING_APPROVAL && status != ProductStatus.DISCONTINUED;
    }

    private void requireCanTransitionTo(ProductStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidProductStatusTransitionException(id, status, target);
        }
    }

    private void requireStatus(ProductStatus required, String action) {
        if (status != required) {
            throw new InvalidProductStatusTransitionException(id,
                    "cannot be %s while %s (must be %s)".formatted(action, status, required));
        }
    }

    private static String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code must match " + CODE.pattern());
        }
        return code;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public ProductId id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String nameEn() { return nameEn; }
    public UUID brandId() { return brandId; }
    public UUID categoryId() { return categoryId; }
    public String shortDescription() { return shortDescription; }
    public String description() { return description; }
    public String descriptionEn() { return descriptionEn; }
    public TaxClass taxClass() { return taxClass; }
    public ProductKind kind() { return kind; }
    public ProductStatus status() { return status; }
    public long version() { return version; }
    public UUID submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant discontinuedAt() { return discontinuedAt; }
}
