package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;

import java.util.UUID;

/**
 * The VAT group of the product a SKU belongs to (WF-05 D-8): what the accountant reads before changing it, from the SKU
 * an invoice line names - a customer's SKU is a variant of the product whose class it takes.
 *
 * @param sku      the SKU asked about, as stored; null when the product was read by its id
 * @param taxClass null while it was never set (the product is then invoiced at the highest rate, Q-21)
 * @param version  the product's version, quoted back by the change
 */
public record ProductTaxClass(String sku, UUID productId, String productCode, String productName,
                              ProductStatus status, TaxClass taxClass, long version) {
}
