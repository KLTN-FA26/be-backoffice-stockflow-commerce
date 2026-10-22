package com.stockflow.fulfillment.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * <b>One part of a kit</b> ("bộ", SCRUM-287 LP2-5/LP2-6): a unit whose inventory item ships as {@code package_count}
 * N &gt; 1 boxes - a table whose top and base travel in separate cartons - is packed as N parcels that share a kit
 * group. Part 1 holds the unit (its {@code package_line}); parts 2..N hold no goods of their own, only the kit code.
 * All parts are of one order (08 BR-04), are sealed with their own weight and size, and ship in one shipment. Load
 * planning keeps a kit's parts together per vehicle by {@code groupId}. {@code orderLineId} is the line whose unit the
 * kit was opened for: part 1 takes that line's item and no other.
 *
 * <p>The label prints it as "Bộ {@code partNo}/{@code partCount}".</p>
 */
public record KitPart(UUID groupId, int partNo, int partCount, UUID orderLineId) {

    /** The fewest parts of a kit: a unit that ships as one box is an ordinary parcel. */
    public static final int MIN_PARTS = 2;
    /** The most parts one kit has: {@code smallint} in the table, and far above any real item. */
    public static final int MAX_PARTS = 99;

    public KitPart {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(orderLineId, "orderLineId");
        if (partCount < MIN_PARTS || partCount > MAX_PARTS || partNo < 1 || partNo > partCount) {
            throw new IllegalArgumentException("kit part %d of %d".formatted(partNo, partCount));
        }
    }

    /** Part 1 carries the unit; the others carry none. */
    public boolean holdsGoods() {
        return partNo == 1;
    }
}
