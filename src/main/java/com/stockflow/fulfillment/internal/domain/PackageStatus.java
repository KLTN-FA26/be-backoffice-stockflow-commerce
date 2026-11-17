package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a row of {@code fulfillment.package}. Mapped {@code EnumType.STRING}. The table holds two kinds of row,
 * never mixed ({@code ck_package_kind}):
 *
 * <p>A <b>shipping carton</b> of the check station (team plan 4C-6, kltn-docs 08 §5.2) - the LPN carton the producer
 * packed, picked for the order:</p>
 * <pre>
 *   TO_CHECK ─LPN scanned─▶ CHECKED ─labelled─▶ LABELED ─in a shipment─▶ (ships)
 *      └─────────────────────┴───────────────────┴─▶ REMOVED (damaged; or its order cancelled before any label;
 *                                                    a LABELED carton only while in no shipment)
 * </pre>
 *
 * <p>A <b>parcel</b> of the repack station (kltn-docs 08 before 2026-11-04), kept until team plan 4C-7 removes it:</p>
 * <pre>
 *   OPEN ─weighed, measured, sealed─▶ SEALED ─in a shipment─▶ (ships)
 *    ▲  └──────────────┐                 │
 *    └──re-opened──────┼─────────────────┘  (only while it is in no shipment)
 *                      └─▶ VOIDED (taken apart before it shipped; its goods count as unpacked again)
 * </pre>
 */
public enum PackageStatus {
    OPEN, SEALED, VOIDED, TO_CHECK, CHECKED, LABELED, REMOVED;

    /** What a shipment carries and loading takes: a sealed parcel or a labelled carton (09). */
    public boolean ships() {
        return this == SEALED || this == LABELED;
    }

    /** No longer part of anything that ships: a voided parcel or a removed carton. */
    public boolean gone() {
        return this == VOIDED || this == REMOVED;
    }
}
