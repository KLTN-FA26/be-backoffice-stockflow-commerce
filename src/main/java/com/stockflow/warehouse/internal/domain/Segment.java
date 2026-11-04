package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * A straight line on the warehouse map, in its map unit: where a wall or a door runs. Coordinates at
 * scale 3 like every measure on the map ({@code NUMERIC(10,3)}), never negative
 * ({@code ck_boundary_position}), and the two ends apart ({@code ck_boundary_length}).
 */
public record Segment(BigDecimal startX, BigDecimal startY, BigDecimal endX, BigDecimal endY) {

    public Segment {
        startX = DomainChecks.measure("startX", startX);
        startY = DomainChecks.measure("startY", startY);
        endX = DomainChecks.measure("endX", endX);
        endY = DomainChecks.measure("endY", endY);
        if (startX.signum() < 0 || startY.signum() < 0 || endX.signum() < 0 || endY.signum() < 0) {
            throw DomainChecks.invalid("A boundary cannot reach a negative position");
        }
        if (startX.compareTo(endX) == 0 && startY.compareTo(endY) == 0) {
            throw DomainChecks.invalid("A boundary needs two different ends");
        }
    }

    /** BR-06: both ends inside a frame anchored at the origin; on its edge is inside. */
    public boolean fitsWithin(BigDecimal frameWidth, BigDecimal frameLength) {
        return startX.max(endX).compareTo(frameWidth) <= 0 && startY.max(endY).compareTo(frameLength) <= 0;
    }
}
