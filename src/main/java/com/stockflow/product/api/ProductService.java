package com.stockflow.product.api;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditEntry;

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

    ProductSummary create(CreateProductCommand command);

    Optional<ProductSummary> findById(UUID productId);

    /** Paginated, filterable list. {@code images} is empty on every row — see {@link ProductSummary}. */
    PageResponse<ProductSummary> list(ListProductsQuery query);

    ProductSummary update(UpdateProductCommand command);

    /** DRAFT → PENDING_APPROVAL. Requires a category (BR-PRD-001, minimal subset — see SCRUM-57). */
    ProductSummary submit(UUID productId, UUID submittedBy);

    /** PENDING_APPROVAL → APPROVED. BR-PRD-003: {@code approverId} must not be who submitted it. */
    ProductSummary approve(UUID productId, UUID approverId);

    /** PENDING_APPROVAL → DRAFT. */
    ProductSummary reject(UUID productId, UUID approverId, String reason);

    /** APPROVED/PUBLISHED → DISCONTINUED. Terminal — see {@code Product.discontinue}'s javadoc for
     *  the BR-PRD-005 gap this does not yet enforce. */
    ProductSummary discontinue(UUID productId);

    /**
     * SCRUM-86 (WBS 3.1.8.2): this product's audit trail, newest first — every create/update/
     * submit/approve/reject/discontinue recorded against it. Throws {@code PRODUCT_NOT_FOUND} if
     * no such product exists, so a bad id gets 404 rather than a confusing empty page.
     */
    PageResponse<AuditEntry> history(UUID productId, int page, int size);
}
