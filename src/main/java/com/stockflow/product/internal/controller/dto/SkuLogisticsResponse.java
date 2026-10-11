package com.stockflow.product.internal.controller.dto;

import com.stockflow.inventory.api.StorageClass;

import java.math.BigDecimal;
import java.util.UUID;

/** The logistics of one SKU, as its inventory item holds them. */
public record SkuLogisticsResponse(UUID skuId, String sku, long version, String unitOfMeasure, String barcode,
                                   BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                                   BigDecimal packageWeightKg, BigDecimal packageLengthCm, BigDecimal packageWidthCm,
                                   BigDecimal packageHeightCm, int packageCount, int packSize,
                                   StorageClass storageClass, boolean requiresAdultSignature,
                                   String shippingRestrictionNote, boolean qcRequired) {
}
