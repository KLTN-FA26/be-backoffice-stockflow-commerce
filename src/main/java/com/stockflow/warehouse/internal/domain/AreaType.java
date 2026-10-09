package com.stockflow.warehouse.internal.domain;

/**
 * What a floor area is for. Mapped {@code EnumType.STRING}.
 *
 * <p>Every type except {@code NON_STORAGE} can hold stock (docs module 06 BR-11); an office or a
 * charging bay is on the map only so it can be drawn and walked around.</p>
 */
public enum AreaType {
    /** WH/Input: goods just received, before QC or putaway. */
    RECEIVING,
    /** WH/Quality Control: goods of a QC-required SKU waiting for inspection (docs 03, 3-step flow). */
    QUALITY_CONTROL,
    /** After QC: goods put on hold or rejected, waiting for a decision or the return to the supplier. */
    QUARANTINE,
    PACKING,
    DISPATCH,
    OVERFLOW,
    NON_STORAGE;

    /**
     * Whether an area of this type is a stock location, and so owns a {@code storage_location} row
     * ({@code ck_area_storage}).
     */
    public boolean isStorage() {
        return this != NON_STORAGE;
    }
}
