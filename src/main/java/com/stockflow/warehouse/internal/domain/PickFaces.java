package com.stockflow.warehouse.internal.domain;

/**
 * The sides of a shelf a picker can reach bins from, in the shelf's own frame: north is its top edge
 * before rotation, so turning the shelf turns its faces with it.
 *
 * <p>BR-08: a shelf holding a pickable bin must have at least one face ({@link #hasAny()}). Whether
 * that face is blocked by a wall or another shelf is the other half of BR-08 and belongs to the
 * routing work of SCRUM-91.</p>
 */
public record PickFaces(boolean north, boolean east, boolean south, boolean west) {

    public static final PickFaces NONE = new PickFaces(false, false, false, false);

    public boolean hasAny() {
        return north || east || south || west;
    }
}
