package com.stockflow.warehouse.internal.domain;

/**
 * The unit every coordinate and size on a warehouse map is measured in. Mapped
 * {@code EnumType.STRING}.
 *
 * <p>Only metres for now (docs module 06 §5). An enum rather than a hard-coded assumption so the
 * unit is written down next to the numbers it gives meaning to; it never changes after the
 * warehouse is created, because that would silently rescale every shelf on the map.</p>
 */
public enum MapUnit { M }
