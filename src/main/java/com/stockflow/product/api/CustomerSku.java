package com.stockflow.product.api;

import java.util.UUID;

/**
 * A customer's own SKU ({@code product.customer_skus}, V20270105000100; owner decision Q4 of 2026-11-26).
 *
 * @param id               the SKU's own id (a row of {@code product.customer_skus}, never a catalogue variant's id;
 *                         it kept the id it had as a variant row, so a chat card naming it still does)
 * @param productId        the product it belongs to
 * @param sku              its code: product - customer - number
 * @param name             what it is called on quotes and orders
 * @param ownerCustomerId  the customer who alone may see and order it
 * @param designSnapshotId the approved design it is made to
 * @param sourceVariantId  the catalogue variant (quy cách) the design was drawn on: whose carton the SKU is packed in
 *                         (Q1) and whose standard price a quote of it starts from
 * @param sourceSku        that variant's code
 * @param status           {@code ACTIVE}, {@code BLOCKED} or {@code OBSOLETE}
 */
public record CustomerSku(UUID id, UUID productId, String sku, String name, UUID ownerCustomerId,
                          UUID designSnapshotId, UUID sourceVariantId, String sourceSku, String status) {
}
