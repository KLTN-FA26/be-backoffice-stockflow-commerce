package com.stockflow.warehouse.internal.domain;

/**
 * Storage condition a location offers and a SKU requires. Mapped {@code EnumType.STRING}.
 *
 * <p>A shelf declares a default; a bin may override it. A SKU is only ever put away into a bin
 * whose effective class matches its own - a hard constraint (docs module 06 BR-01).</p>
 */
public enum StorageClass { NORMAL, COLD, HAZMAT, FRAGILE, OVERSIZE }
