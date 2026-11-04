package com.stockflow.fulfillment.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/**
 * A kit just opened (SCRUM-287 LP2-6): its parts, part 1 first, each an OPEN package carrying {@code kit}.
 *
 * @param orderLineId the line whose unit it holds; scan that unit into part 1
 */
public record PackageKitResponse(UUID kitGroupId, UUID orderLineId, String sku, int partCount,
                                 List<PackageResponse> packages) {
}
