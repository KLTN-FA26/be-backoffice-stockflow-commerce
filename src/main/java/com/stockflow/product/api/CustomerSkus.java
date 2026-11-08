package com.stockflow.product.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's own SKUs (kltn-docs 12 §4.5, 01 §4.8, B20): issued when the Sales Manager approves a customer's design,
 * the only goods that are stocked, quoted and ordered, and visible to their owner alone (13 BR-02).
 */
public interface CustomerSkus {

    /**
     * Issues the customer's SKU for an approved design snapshot, in the caller's transaction (the approval's: the design
     * is approved, frozen and given its SKU in one step, or not at all - 12 BR-09). The code is the product's code, the
     * customer's code and the next number for that customer on that product; the SKU is ACTIVE at once, and its item
     * gets the source variant's carton spec, logistics and stock policy. Issuing again for the same snapshot returns
     * the SKU issued before.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VARIANT_NOT_FOUND} for an unknown source SKU;
     *         {@code PRODUCT_NOT_CUSTOMIZABLE}; {@code CUSTOMER_SKU_SOURCE_UNAVAILABLE} when the product or the source
     *         variant is not on sale, or the source is itself a customer's SKU; {@code CONFLICT} when the snapshot
     *         already has a SKU of another customer or variant
     */
    CustomerSku issue(IssueCustomerSkuCommand command);

    /** The SKU issued for an approved design snapshot, if any (one per snapshot: uk_variants_design_snapshot). */
    java.util.Optional<CustomerSku> ofSnapshot(java.util.UUID designSnapshotId);

    /**
     * Refuses any of {@code skus} that is another customer's own SKU, as if it did not exist (kltn-docs 13 BR-02: other
     * customers' SKUs are invisible): what every order and quote line for {@code customerId} passes through. Ordinary
     * variants and unknown SKUs pass; whoever reads them next decides about those.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VARIANT_NOT_FOUND}
     */
    void requireVisibleTo(java.util.Collection<String> skus, UUID customerId);

    /** The owner of {@code sku}, or empty for an ordinary variant (or an unknown SKU). */
    Optional<UUID> ownerOf(String sku);

    /** The customer's own SKUs, of one product when {@code productId} is not null. */
    List<CustomerSku> ownedBy(UUID customerId, UUID productId);
}
