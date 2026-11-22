package com.stockflow.inventory.api;

/**
 * The physical data of one SKU's inventory item (docs 01 BR-08) that is not the carton's: the base unit stock is
 * counted in, the barcode, and whether its cartons may be turned.
 *
 * <p>The item carries no weight or size of its own, of one unit or of a shipped box (V20261215000100, kltn-docs 01 §3,
 * BR-03, C21, chốt lại 2026-11-22): every measure the business uses is the carton's, on the item's one carton unit
 * ({@link UomConversion}, {@link CartonSpec}). The shipped box and its kit of several boxes belonged to parcel
 * shipping, which is gone.</p>
 *
 * <p>{@code rotatable}: whether a carton of this SKU may be turned any of 6 ways when a truck or
 * container is loaded (docs 01 §3 "Cho phép xoay khi xếp xe", default on, E13). It is the default for
 * the package's own flag at packing (08) and is read by load planning (09). Putaway, put-back and moves
 * read it too: a rotatable carton may be turned on the horizontal to fit a bin, length for width (06 BR-01).</p>
 *
 * <p>No storage class, adult-signature flag or shipping-restriction note any more (kltn-docs 06, storage class removed
 * 2026-11-05; team plan 5-4): which bin takes a carton is decided by its size alone, and nothing read
 * the other two.</p>
 */
public record ItemLogistics(
        String unitOfMeasure,
        String barcode,
        boolean rotatable
) {
    /** Logistics without an opinion on rotation: the documented default, rotatable (docs 01, E13). */
    public ItemLogistics(String unitOfMeasure, String barcode) {
        this(unitOfMeasure, barcode, true);
    }
}
