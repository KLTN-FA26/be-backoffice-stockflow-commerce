package com.stockflow.product.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.contracts.ProductApproved;
import com.stockflow.contracts.ProductDiscontinued;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>Aggregate root of the product module: one product master row</b> ({@code product.products}).
 *
 * <p>SCRUM-56 (WBS 3.1.1.2) landed the master-data fields; SCRUM-57 (WBS 3.1.1.3) adds the
 * approval workflow on top: {@code submit()}/{@code approve()}/{@code reject()}. SCRUM-85
 * (WBS 3.1.8.1) adds {@code discontinue()}, the terminal edge, and SCRUM-461 adds the reason it
 * needs, {@code suspend()}/{@code resume()} (kltn-docs 01 §5.1 "Inactive"), the brand a submission
 * needs, and the version check an edit quotes — all following {@link ProductStatus#canTransitionTo}'s
 * table. {@link #updateDetails} guards on {@code DRAFT}.</p>
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

    /** {@code product.products.discontinue_reason} / {@code suspend_reason}: the longest reason stored. */
    public static final int MAX_REASON_LENGTH = 500;

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
    private String discontinueReason;
    private Instant suspendedAt;
    private String suspendReason;

    public Product(ProductId id, String code, String name, String nameEn, UUID brandId, UUID categoryId,
                   String shortDescription, String description, String descriptionEn, TaxClass taxClass,
                   ProductKind kind, ProductStatus status, long version,
                   UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                   String rejectionReason, Instant discontinuedAt, String discontinueReason,
                   Instant suspendedAt, String suspendReason) {
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
        this.discontinueReason = discontinueReason;
        this.suspendedAt = suspendedAt;
        this.suspendReason = suspendReason;
    }

    /** A new product master row, always born {@link ProductStatus#DRAFT}. */
    public static Product draft(String code, String name, String nameEn, UUID brandId, UUID categoryId,
                                String shortDescription, String description, String descriptionEn,
                                TaxClass taxClass, ProductKind kind) {
        return new Product(ProductId.newId(), code, name, nameEn, brandId, categoryId, shortDescription,
                description, descriptionEn, taxClass, kind, ProductStatus.DRAFT, 0L,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Replace every editable field. {@code code} is not a parameter: it is fixed at creation. Returns the
     * names of the fields whose value changed, for the product's history.
     *
     * <p>{@code DRAFT}-only: no business rule defines what an edit means once a product is
     * {@code PENDING_APPROVAL} or {@code APPROVED} — refusing outright is the honest choice until
     * product/PM decide whether an edit should reset the workflow or is allowed to pass through.
     * The selling content (slug, SEO) is edited through the catalog and is not affected.</p>
     */
    public List<String> updateDetails(String name, String nameEn, UUID brandId, UUID categoryId,
                                      String shortDescription, String description, String descriptionEn,
                                      TaxClass taxClass, ProductKind kind) {
        requireStatus(ProductStatus.DRAFT, "edited");
        String newName = requireNonBlank(name, "name");
        ProductKind newKind = Objects.requireNonNullElse(kind, ProductKind.STANDARD);
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(this.name, newName)) changed.add("name");
        if (!Objects.equals(this.nameEn, nameEn)) changed.add("nameEn");
        if (!Objects.equals(this.brandId, brandId)) changed.add("brandId");
        if (!Objects.equals(this.categoryId, categoryId)) changed.add("categoryId");
        if (!Objects.equals(this.shortDescription, shortDescription)) changed.add("shortDescription");
        if (!Objects.equals(this.description, description)) changed.add("description");
        if (!Objects.equals(this.descriptionEn, descriptionEn)) changed.add("descriptionEn");
        if (this.taxClass != taxClass) changed.add("taxClass");
        if (this.kind != newKind) changed.add("kind");
        this.name = newName;
        this.nameEn = nameEn;
        this.brandId = brandId;
        this.categoryId = categoryId;
        this.shortDescription = shortDescription;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.taxClass = taxClass;
        this.kind = newKind;
        return List.copyOf(changed);
    }

    /**
     * An edit quotes the version it read (SCRUM-461): a product changed since answers
     * {@code OPTIMISTIC_LOCK}. The row is locked when this is called, so the comparison cannot be
     * overtaken between the check and the write.
     */
    public void requireVersion(long expected) {
        if (expected != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Product %s changed since it was read (version %d, now %d); reload it".formatted(id, expected, version));
        }
    }

    /**
     * Submit for approval. BR-PRD-001's fuller precondition (UoM, dimensions) is about the SKUs'
     * inventory items now, not the product; what the product itself must have is a category and,
     * since a product is identified by name, code, category and brand (kltn-docs 01 §3), a brand —
     * {@code ck_products_brand_on_submit} says it again. A DRAFT may be saved without one. The product
     * always has at least one variant — it is created with one.
     */
    public void submit(UUID submittedBy, Instant now) {
        requireCanTransitionTo(ProductStatus.PENDING_APPROVAL);
        if (categoryId == null) {
            throw new InvalidProductStatusTransitionException(id,
                    "cannot be submitted for approval: a category is required (BR-PRD-001, minimal subset)");
        }
        if (brandId == null) {
            String message = "A brand is required before submitting for approval";
            throw new BusinessException(ErrorCode.PRODUCT_BRAND_REQUIRED, message,
                    List.of(new FieldError("brandId", message, ErrorCode.PRODUCT_BRAND_REQUIRED.name())));
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
     * Terminal (WBS 3.1.8.1). Valid from {@code APPROVED}, {@code PUBLISHED} or {@code INACTIVE} per
     * {@link ProductStatus#canTransitionTo}. {@code reason} is mandatory (kltn-docs 01 §5.2) and is what
     * {@code ck_products_discontinue_reason} requires of a discontinued row; {@code discontinuedAt} is
     * what {@code ck_products_discontinued} requires.
     *
     * <p><b>BR-PRD-005 is a warning, not a block</b>: discontinuing leaves the remaining stock and the
     * orders and purchase orders already running to be processed (kltn-docs 01 step 12, 02 edge cases).
     * Which of them still reference the product is read by the application service
     * ({@code SkuReferenceProbe}), which needs other modules; it is shown to the person, never refused.
     * What a discontinued product cannot do is take new purchase order lines.</p>
     */
    public void discontinue(String reason, Instant now) {
        requireCanTransitionTo(ProductStatus.DISCONTINUED);
        this.discontinueReason = requireReason(reason);
        this.status = ProductStatus.DISCONTINUED;
        this.discontinuedAt = Objects.requireNonNull(now, "now");
        registerEvent(new ProductEvent.Discontinued(new ProductDiscontinued(id.value(), now)));
    }

    /**
     * Temporarily off sale (kltn-docs 01 §5.1): {@code APPROVED} or {@code PUBLISHED} to {@code INACTIVE}.
     * {@code publishedAt} stays; the catalog hides a product that is not {@code PUBLISHED}, and is told to
     * look again. When and why are what {@code ck_products_inactive} requires of an INACTIVE row.
     */
    public void suspend(String reason, Instant now) {
        requireCanTransitionTo(ProductStatus.INACTIVE);
        this.suspendReason = requireReason(reason);
        this.status = ProductStatus.INACTIVE;
        this.suspendedAt = Objects.requireNonNull(now, "now");
        registerEvent(new ProductEvent.CatalogChanged(new CatalogListingChanged(id.value())));
    }

    /**
     * Back on the books: {@code INACTIVE} to {@code APPROVED} (the docs' "Active" is APPROVED in the code).
     * It returns to the storefront only by being published again, which re-checks the gallery and the price.
     */
    public void resume(Instant now) {
        // Not requireCanTransitionTo(APPROVED): PENDING_APPROVAL and PUBLISHED may move there too (approving,
        // unpublishing), and neither of those is a resumption.
        requireStatus(ProductStatus.INACTIVE, "resumed");
        Objects.requireNonNull(now, "now");
        this.status = ProductStatus.APPROVED;
        this.suspendedAt = null;
        this.suspendReason = null;
        registerEvent(new ProductEvent.CatalogChanged(new CatalogListingChanged(id.value())));
    }

    /** Only a product that was never submitted, or was sent back, is deleted (kltn-docs 01 BR-02). */
    public void requireDeletable() {
        if (status != ProductStatus.DRAFT) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_DELETABLE,
                    "Product %s is %s; only a DRAFT product can be deleted".formatted(id, status));
        }
    }

    /** Whether the product's media and variants may be changed now: not while it is under review,
     *  and never once it is discontinued. A suspended product is still maintained. */
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

    /** A reason people can read later: not blank, at most the column's length; stored stripped. */
    private static String requireReason(String reason) {
        String value = reason == null ? "" : reason.strip();
        if (value.isEmpty() || value.length() > MAX_REASON_LENGTH) {
            String message = "A reason of 1.." + MAX_REASON_LENGTH + " characters is required";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldError("reason", message, value.isEmpty() ? "NotBlank" : "Size")));
        }
        return value;
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
    public String discontinueReason() { return discontinueReason; }
    public Instant suspendedAt() { return suspendedAt; }
    public String suspendReason() { return suspendReason; }
}
