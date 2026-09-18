package com.stockflow.warehouse.internal.domain;

/**
 * Physical kind of a bin. Mapped {@code EnumType.STRING}.
 *
 * <p>Descriptive and used for soft scoring only - a pallet load prefers a {@code PALLET} bin but is
 * not barred from others (docs open question C13).</p>
 */
public enum BinType { STANDARD, PALLET, SMALL_PARTS }
