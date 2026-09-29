package com.stockflow.catalog.internal.controller.dto;
import java.math.BigDecimal;
public record SkuPriceResponse(String sku,long revision,BigDecimal basePrice,String baseCurrency,
                               BigDecimal effectivePrice,String effectiveCurrency) {}
