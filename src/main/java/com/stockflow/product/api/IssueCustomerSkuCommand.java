package com.stockflow.product.api;

import java.util.UUID;

/**
 * Issue a customer's own SKU for an approved design.
 *
 * @param designSnapshotId the approved design snapshot the SKU is made to; one SKU per snapshot
 * @param customerId       the owner
 * @param customerCode     the customer as the SKU code spells them ({@code CustomerSummary.code()}): capitals, digits
 *                         and hyphens, at most 20 characters
 * @param sourceSku        the variant the design was drawn on
 * @param name             what the SKU is called, usually the design's name; the source variant's name when blank
 */
public record IssueCustomerSkuCommand(UUID designSnapshotId, UUID customerId, String customerCode, String sourceSku,
                                      String name) {
}
