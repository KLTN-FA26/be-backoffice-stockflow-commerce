package com.stockflow.product.api;

/**
 * Storage condition a SKU needs, which decides the kind of bin it may be put away into
 * (warehouse-map rules in docs/warehouse/06). One value per SKU, {@link #NORMAL} unless said
 * otherwise.
 *
 * <p>Deliberately separate from the {@code hazmat} / {@code oversized} flags: those are
 * carrier-facing shipping restrictions (SCRUM-76), this is what the warehouse needs to hold the
 * goods. A hazmat item is usually {@link #HAZMAT} in storage too, but nothing forces the two to
 * agree — an oversized sofa is shipped as oversized and stored as {@link #OVERSIZE}, yet a
 * fragile lamp can be hazmat-shipped and stored {@link #FRAGILE}.</p>
 */
public enum StorageClass {
    NORMAL,
    COLD,
    HAZMAT,
    FRAGILE,
    OVERSIZE
}
