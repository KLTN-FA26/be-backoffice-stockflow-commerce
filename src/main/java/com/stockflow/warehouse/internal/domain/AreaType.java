package com.stockflow.warehouse.internal.domain;

/**
 * What a floor area is for. Mapped {@code EnumType.STRING}.
 *
 * <p>Every type except {@code NON_STORAGE} can hold stock (docs module 06 BR-11); an office or a
 * charging bay is on the map only so it can be drawn and walked around.</p>
 */
public enum AreaType {
    /** WH/Input: goods just received, before putaway. */
    RECEIVING,
    /**
     * Goods on hold: cartons a warehouse manager quarantined, and goods that came back on a return receipt, waiting for
     * a release or a write-off (kltn-docs 20 §4.8, 03 BR-14). The QUALITY_CONTROL area of the QC era became one
     * (team plan 4D-4, V20261114000200).
     */
    QUARANTINE,
    /**
     * The dispatch area: picked cartons are brought here, checked and labelled where they stand, grouped by shipment and
     * wait for the vehicle (kltn-docs 06 BR-22). The former PACKING area merged into it (V20261113000600).
     */
    DISPATCH,
    /**
     * The staging area (kltn-docs 06 §4.5, BR-18; 07 §1): cartons picked for an order that may not leave yet, moved
     * here whole and still held for it - out of available to promise, never moved by hand (11 BR-09, 20 BR-20). Stock
     * arrives only by picking, so it is never pickable nor a putaway target ({@link Area}).
     */
    STAGING,
    OVERFLOW,
    /** The print shop: blanks issued for a production order, printed there (V20261010000200, SCRUM-397). */
    PRODUCTION,
    NON_STORAGE;

    /**
     * Whether an area of this type is a stock location, and so owns a {@code storage_location} row
     * ({@code ck_area_storage}).
     */
    public boolean isStorage() {
        return this != NON_STORAGE;
    }
}
