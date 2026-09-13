package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import com.stockflow.contracts.ProductApproved;
import com.stockflow.contracts.ProductDiscontinued;
import com.stockflow.common.domain.AggregateRoot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Aggregate root of the product module: one product master row.</b>
 *
 * <p>SCRUM-56 (WBS 3.1.1.2) landed the master-data fields; SCRUM-57 (WBS 3.1.1.3) adds the
 * approval workflow on top: {@code submit()}/{@code approve()}/{@code reject()}. SCRUM-85
 * (WBS 3.1.8.1) adds {@code discontinue()}, the terminal edge — all following
 * {@link ProductStatus#canTransitionTo}'s table. {@link #updateDetails} guards on
 * {@code DRAFT} for real — that guard was a dead branch before SCRUM-57 added any other
 * reachable status.</p>
 *
 * <p>No Spring, no JPA, no annotations — {@code ArchitectureTest.domainDoesNotDependOnFrameworks}
 * enforces it. {@code code} is immutable once created (like {@code Sku}/{@code OrderNumber}); every
 * other field goes through {@link #updateDetails}. Transition guards throw
 * {@link InvalidProductStatusTransitionException}/{@link SelfApprovalNotAllowedException}
 * (dedicated {@code BusinessException}s), never {@code IllegalStateException} — see
 * {@code InvalidProductStatusTransitionException}'s javadoc for why that distinction matters here.</p>
 */
public final class Product extends AggregateRoot {

    private final ProductId id;
    private final String code;

    private String name;
    private String nameEn;
    private UUID categoryId;
    private String description;
    private String descriptionEn;
    private String brand;
    private TaxClass taxClass;
    private boolean customizable;
    private final List<ProductImage> images;
    private ProductStatus status;
    private final long version;

    /** SCRUM-74 (WBS 3.1.3.1): shipping weight/dimensions, for later rate/carton calculations.
     *  Nullable — a product can exist before these are known; when set, each must be positive. */
    private BigDecimal weightKg;
    private BigDecimal lengthCm;
    private BigDecimal widthCm;
    private BigDecimal heightCm;

    /** Read-only: who/when created this row. Carried for the API response, never checked by an
     *  invariant — same treatment {@code version} gets, not a protected business rule. */
    private final Instant createdAt;
    private final String createdBy;

    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private String rejectionReason;

    public Product(ProductId id, String code, String name, String nameEn, UUID categoryId,
                   String description, String descriptionEn, String brand, TaxClass taxClass,
                   boolean customizable, List<ProductImage> images, ProductStatus status,
                   long version, Instant createdAt, String createdBy,
                   UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                   String rejectionReason, BigDecimal weightKg, BigDecimal lengthCm,
                   BigDecimal widthCm, BigDecimal heightCm) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = requireNonBlank(code, "code");
        this.name = requireNonBlank(name, "name");
        this.nameEn = requireNonBlank(nameEn, "nameEn");
        this.categoryId = categoryId;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.brand = requireNonBlank(brand, "brand");
        this.taxClass = Objects.requireNonNull(taxClass, "taxClass");
        this.customizable = customizable;
        this.images = new ArrayList<>(images == null ? List.of() : images);
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.rejectionReason = rejectionReason;
        this.weightKg = requirePositive(weightKg, "weightKg");
        this.lengthCm = requirePositive(lengthCm, "lengthCm");
        this.widthCm = requirePositive(widthCm, "widthCm");
        this.heightCm = requirePositive(heightCm, "heightCm");
    }

    /** A new product master row, always born {@link ProductStatus#DRAFT}. */
    public static Product draft(String code, String name, String nameEn, UUID categoryId,
                                String description, String descriptionEn, String brand,
                                TaxClass taxClass, boolean customizable, List<ProductImage> images,
                                BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                                BigDecimal heightCm) {
        return new Product(ProductId.newId(), code, name, nameEn, categoryId, description,
                descriptionEn, brand, taxClass, customizable, images, ProductStatus.DRAFT, 0L,
                null, null, null, null, null, null, null, weightKg, lengthCm, widthCm, heightCm);
    }

    /**
     * Replace every editable field. {@code code} is not a parameter: it is fixed at creation, the
     * same way {@code Sku}/{@code OrderNumber} are immutable once assigned elsewhere in this
     * codebase.
     *
     * <p>{@code DRAFT}-only: no business rule defines what an edit means once a product is
     * {@code PENDING_APPROVAL} or {@code APPROVED} — refusing outright is the honest choice until
     * product/PM decide whether an edit should reset the workflow or is allowed to pass through.</p>
     */
    public void updateDetails(String name, String nameEn, UUID categoryId, String description,
                              String descriptionEn, String brand, TaxClass taxClass,
                              boolean customizable, List<ProductImage> images,
                              BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                              BigDecimal heightCm) {
        requireStatus(ProductStatus.DRAFT, "edited");
        this.name = requireNonBlank(name, "name");
        this.nameEn = requireNonBlank(nameEn, "nameEn");
        this.categoryId = categoryId;
        this.description = description;
        this.descriptionEn = descriptionEn;
        this.brand = requireNonBlank(brand, "brand");
        this.taxClass = Objects.requireNonNull(taxClass, "taxClass");
        this.customizable = customizable;
        this.images.clear();
        this.images.addAll(images == null ? List.of() : images);
        this.weightKg = requirePositive(weightKg, "weightKg");
        this.lengthCm = requirePositive(lengthCm, "lengthCm");
        this.widthCm = requirePositive(widthCm, "widthCm");
        this.heightCm = requirePositive(heightCm, "heightCm");
    }

    /**
     * Submit for approval. BR-PRD-001's fuller precondition (name + category + UoM + ≥1 variant +
     * dimensions) is WBS 3.1.9.1, a later story — those fields don't exist on this module yet, so
     * only the minimal subset this module can actually check applies: a category must be set.
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
     * {@link ProductStatus#canTransitionTo}.
     *
     * <p><b>BR-PRD-005 is not enforced here</b> ("no open PO and no unshipped order line
     * references it"): checking it needs {@code procurement} (still an empty skeleton — there is
     * nothing to check against yet) and a cross-module read of {@code order}, which this module
     * does not depend on today. Enforcing it properly belongs in the application service once
     * procurement exists, not as a silent no-op invariant here — flagged rather than faked.</p>
     */
    public void discontinue(Instant now) {
        requireCanTransitionTo(ProductStatus.DISCONTINUED);
        this.status = ProductStatus.DISCONTINUED;
        registerEvent(new ProductEvent.Discontinued(
                new ProductDiscontinued(id.value(), Objects.requireNonNull(now, "now"))));
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** {@code null} means "not yet known" and passes; a supplied value must be positive. */
    private static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public ProductId id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String nameEn() { return nameEn; }
    public UUID categoryId() { return categoryId; }
    public String description() { return description; }
    public String descriptionEn() { return descriptionEn; }
    public String brand() { return brand; }
    public TaxClass taxClass() { return taxClass; }
    public boolean customizable() { return customizable; }
    public List<ProductImage> images() { return List.copyOf(images); }
    public ProductStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
    public UUID submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String rejectionReason() { return rejectionReason; }
    public BigDecimal weightKg() { return weightKg; }
    public BigDecimal lengthCm() { return lengthCm; }
    public BigDecimal widthCm() { return widthCm; }
    public BigDecimal heightCm() { return heightCm; }
}
