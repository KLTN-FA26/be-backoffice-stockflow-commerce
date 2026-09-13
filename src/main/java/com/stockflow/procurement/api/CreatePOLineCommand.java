package com.stockflow.procurement.api;

import java.math.BigDecimal;

public record CreatePOLineCommand(String sku, String description, int quantityOrdered,
                                  BigDecimal unitPrice) {
}
