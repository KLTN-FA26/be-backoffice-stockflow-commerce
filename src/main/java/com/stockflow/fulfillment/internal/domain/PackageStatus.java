package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a parcel at the packing station (kltn-docs 08 steps 4-7). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   OPEN ─weighed, measured, sealed─▶ SEALED ─in a shipment─▶ (ships)
 *    ▲  └──────────────┐                 │
 *    └──re-opened──────┼─────────────────┘  (only while it is in no shipment)
 *                      └─▶ VOIDED (taken apart before it shipped; its goods count as unpacked again)
 * </pre>
 */
public enum PackageStatus { OPEN, SEALED, VOIDED }
