package com.stockflow.inventory.api;

/**
 * The physical data of one SKU's inventory item (docs 01 BR-08) that is not the carton's: the base unit stock is
 * counted in, and the barcode.
 *
 * <p>The item carries no weight or size of its own, of one unit or of a shipped box (V20261215000100, kltn-docs 01 §3,
 * BR-03, C21): every measure the business uses is the carton's, and the carton is the catalogue variant's
 * ({@link UomConversion}, {@link CartonSpec}; owner decision Q1). Whether a carton may be turned is the variant's too
 * (owner decision Q9, V20261231000200): it left the item with the carton, because a copy on every customer's SKU is
 * what let a fix miss the SKUs already issued.</p>
 *
 * <p>No storage class, adult-signature flag or shipping-restriction note any more (kltn-docs 06, storage class removed
 * 2026-11-05; team plan 5-4): which bin takes a carton is decided by its size alone, and nothing read
 * the other two.</p>
 */
public record ItemLogistics(
        String unitOfMeasure,
        String barcode
) {
}
