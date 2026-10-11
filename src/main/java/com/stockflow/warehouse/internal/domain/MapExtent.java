package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * How far into a warehouse map anything reaches: the right-most and bottom-most edge of every
 * shelf and area still on the layout (not {@code INACTIVE}, issue #18 D3) and every boundary.
 * The smallest map the warehouse can shrink to (BR-06).
 */
public record MapExtent(BigDecimal right, BigDecimal bottom) {

    /** Nothing on the map yet. */
    public static final MapExtent EMPTY = new MapExtent(BigDecimal.ZERO, BigDecimal.ZERO);

    public MapExtent {
        right = right == null ? BigDecimal.ZERO : right;
        bottom = bottom == null ? BigDecimal.ZERO : bottom;
    }
}
