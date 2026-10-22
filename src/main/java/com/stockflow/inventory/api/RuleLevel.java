package com.stockflow.inventory.api;

/**
 * Which level of the packaging rule chain produced a {@link PackagingSpec} (SCRUM-287, decision LP2-2), in the
 * order the resolver tries them.
 */
public enum RuleLevel {
    /** The unit the item marks as its default shipping unit. */
    DEFAULT_SHIPPING_UNIT,
    /** The largest active packing unit that has its full size and weight. */
    LARGEST_PACKING_UNIT,
    /** The item's own box, times {@code packageCount} (a kit of N same-size boxes when N is above 1; LP2-5). */
    ITEM_BOX,
    /** The item's own unit size and weight. */
    ITEM_UNIT
}
