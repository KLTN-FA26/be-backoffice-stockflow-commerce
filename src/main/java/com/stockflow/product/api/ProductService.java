package com.stockflow.product.api;

import com.stockflow.common.api.PageResponse;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the product module — the only package other modules may import.
 *
 * <p>Every parameter and return type is a record or enum declared in THIS package, never a
 * domain object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal} and
 * {@code theApiPublishesNoEntities} enforce it).</p>
 */
public interface ProductService {

    /** Whether {@code sku} is one of this product's variants. Prevents attaching a valid design for a
     *  different product to an order SKU. */
    boolean containsSku(UUID productId, String sku);

    /** Procurement fallback for an omitted line description: the name of the product the variant
     *  {@code sku} belongs to. Never exposes persistence objects. */
    Optional<String> nameForSku(String sku);

    ProductSummary create(CreateProductCommand command);

    Optional<ProductSummary> findById(UUID productId);

    /** Paginated, filterable list. */
    PageResponse<ProductSummary> list(ListProductsQuery query);

    /**
     * Replaces a DRAFT product's editable fields. The command's {@code version} must be the product's
     * current one.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_NOT_FOUND}, {@code OPTIMISTIC_LOCK},
     *         {@code INVALID_PRODUCT_STATUS_TRANSITION} (not a draft), {@code BRAND_NOT_FOUND},
     *         {@code CATEGORY_NOT_FOUND}
     */
    ProductSummary update(UpdateProductCommand command);

    /**
     * DRAFT → PENDING_APPROVAL. Requires a category (BR-PRD-001, minimal subset — see SCRUM-57) and a brand
     * ({@code PRODUCT_BRAND_REQUIRED}, with {@code fieldErrors[brandId]}; kltn-docs 01 §3).
     */
    ProductSummary submit(UUID productId, UUID submittedBy);

    /** PENDING_APPROVAL → APPROVED. BR-PRD-003: {@code approverId} must not be who submitted it. */
    ProductSummary approve(UUID productId, UUID approverId);

    /** PENDING_APPROVAL → DRAFT. */
    ProductSummary reject(UUID productId, UUID approverId, String reason);

    /**
     * APPROVED, PUBLISHED or INACTIVE → DISCONTINUED. Terminal. {@code reason} is mandatory
     * (kltn-docs 01 §5.2) and every variant not yet obsolete becomes {@code OBSOLETE} with it, the
     * default one included.
     *
     * <p><b>BR-PRD-005 warns, it does not block</b> (kltn-docs 01 step 12, 02 edge cases): purchase order
     * lines and order lines still open are listed in the result, and carry on. What changes is that no
     * <i>new</i> purchase order line takes the SKU ({@link #purchasability}).</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_NOT_FOUND}, {@code OPTIMISTIC_LOCK},
     *         {@code INVALID_PRODUCT_STATUS_TRANSITION}, {@code VALIDATION_FAILED} (reason)
     */
    ProductDiscontinuation discontinue(UUID productId, String reason, long version);

    /**
     * APPROVED or PUBLISHED → INACTIVE (kltn-docs 01 §5.1): not shown, no new purchase order lines;
     * running orders and receipts continue. The catalog hides it.
     */
    ProductSummary suspend(UUID productId, String reason, long version);

    /** INACTIVE → APPROVED. To be on the storefront again it is published again. */
    ProductSummary resume(UUID productId, long version);

    /**
     * Deletes a DRAFT product with its variants, their images and the inventory items the variants
     * created (kltn-docs 01 BR-02); the stored image files go after the commit.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCT_NOT_FOUND}, {@code OPTIMISTIC_LOCK},
     *         {@code PRODUCT_NOT_DELETABLE} (not a draft), {@code PRODUCT_IN_USE} (stock, a purchase order,
     *         an order, a design or a storefront listing already points at it)
     */
    void deleteDraft(UUID productId, long version);

    /** The open documents that still refer to this product's SKUs, for the person about to decide. */
    OpenReferences openReferences(UUID productId);

    /**
     * Whether each SKU may go on a new purchase order line (kltn-docs 01 BR-04, 02 BR-01). Every SKU
     * asked for is a key, upper-cased; one no variant carries is {@code VARIANT_NOT_ACTIVE}.
     */
    Map<String, Purchasability> purchasability(Collection<String> skus);

    /**
     * {@link #purchasability}, for a step that commits to the answer: a quote sent, accepted or converted, a
     * sample put into production (review of #109, item 6). The products asked about stay read-locked until the
     * caller's transaction ends, so a suspension or a discontinuation either committed first and is seen, or
     * waits until the step has committed - a step never goes through on an answer that has just stopped being
     * true. Must run inside the caller's transaction.
     */
    Map<String, Purchasability> lockedPurchasability(Collection<String> skus);

    /**
     * SCRUM-86 (WBS 3.1.8.2): this product's history, newest first — its status moves with the reason,
     * and what changed on its variants, images, logistics and stock policy. Throws
     * {@code PRODUCT_NOT_FOUND} if no such product exists, so a bad id gets 404 rather than a confusing
     * empty page.
     */
    PageResponse<ProductChange> history(UUID productId, int page, int size);
}
