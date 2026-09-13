package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import com.stockflow.common.domain.AggregateRoot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Aggregate root of the product module: one product master row.</b>
 *
 * <p>This is the SCRUM-56/WBS 3.1.1.2 slice: master data fields only. Every product created here
 * is {@code DRAFT} — the approval workflow ({@code submit}/{@code approve}/{@code reject}) lands
 * on the stacked SCRUM-57 branch, together with the new {@code ProductStatus} values it needs.
 * That is why {@link #updateDetails} does not yet guard on status: with only {@code DRAFT} ever
 * reachable, a "must be DRAFT" check would be an untestable dead branch. It becomes a real guard
 * the day {@code submit()} exists.</p>
 *
 * <p>No Spring, no JPA, no annotations — {@code ArchitectureTest.domainDoesNotDependOnFrameworks}
 * enforces it. {@code code} is immutable once created (like {@code Sku}/{@code OrderNumber}); every
 * other field goes through {@link #updateDetails}.</p>
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

    /** Read-only: who/when created this row. Carried for the API response, never checked by an
     *  invariant — same treatment {@code version} gets, not a protected business rule. */
    private final Instant createdAt;
    private final String createdBy;

    public Product(ProductId id, String code, String name, String nameEn, UUID categoryId,
                   String description, String descriptionEn, String brand, TaxClass taxClass,
                   boolean customizable, List<ProductImage> images, ProductStatus status,
                   long version, Instant createdAt, String createdBy) {
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
    }

    /** A new product master row, always born {@link ProductStatus#DRAFT}. */
    public static Product draft(String code, String name, String nameEn, UUID categoryId,
                                String description, String descriptionEn, String brand,
                                TaxClass taxClass, boolean customizable, List<ProductImage> images) {
        return new Product(ProductId.newId(), code, name, nameEn, categoryId, description,
                descriptionEn, brand, taxClass, customizable, images, ProductStatus.DRAFT, 0L,
                null, null);
    }

    /**
     * Replace every editable field. {@code code} is not a parameter: it is fixed at creation, the
     * same way {@code Sku}/{@code OrderNumber} are immutable once assigned elsewhere in this
     * codebase.
     */
    public void updateDetails(String name, String nameEn, UUID categoryId, String description,
                              String descriptionEn, String brand, TaxClass taxClass,
                              boolean customizable, List<ProductImage> images) {
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
}
