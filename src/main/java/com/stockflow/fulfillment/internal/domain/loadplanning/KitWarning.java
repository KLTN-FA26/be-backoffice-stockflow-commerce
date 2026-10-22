package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * Something about a declared set the planner could not honour as declared (the original's {@code warnings}, which
 * were sentences in Vietnamese; here a code the caller can translate).
 *
 * @param partCode the part concerned, or null when the warning is about the whole set
 * @param count    how many cartons it concerns, or 0
 */
public record KitWarning(String kitName, Type type, String partCode, int count) {

    public enum Type {
        /** Fewer than two of its parts are in the list: it is not a set, and its cartons load loose. */
        FEWER_THAN_TWO_PARTS,
        /** Not enough cartons of some part for even one set: its cartons load loose. */
        NO_COMPLETE_SET,
        /** Cartons of a part beyond the last whole set: they load loose. */
        SURPLUS_PARTS,
        /**
         * The part's cartons do not all have the same sizes and rotatable flag, so the declared set cannot be laid as
         * one: it is not kept, and its cartons load loose.
         */
        PARTS_OF_DIFFERENT_SIZES
    }
}
