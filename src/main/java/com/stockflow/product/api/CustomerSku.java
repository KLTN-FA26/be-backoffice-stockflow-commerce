package com.stockflow.product.api;

import java.util.UUID;

/**
 * A customer's own SKU.
 *
 * @param variantId        the variant that is the SKU
 * @param productId        the product it belongs to
 * @param sku              its code: product - customer - number
 * @param name             what it is called on quotes and orders
 * @param ownerCustomerId  the customer who alone may see and order it
 * @param designSnapshotId the approved design it is made to
 * @param sourceSku        the variant (quy cách) the design was drawn on
 * @param status           {@code ACTIVE}, {@code BLOCKED} or {@code OBSOLETE}
 */
public record CustomerSku(UUID variantId, UUID productId, String sku, String name, UUID ownerCustomerId,
                          UUID designSnapshotId, String sourceSku, String status) {
}
