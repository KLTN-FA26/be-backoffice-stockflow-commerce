package com.stockflow.inventory.api;

/**
 * Which level of the packaging rule chain produced a {@link PackagingSpec} (SCRUM-287, decision LP2-2), in the
 * order the resolver tries them. The item's box and the item's unit were levels too, until the item lost its own
 * measures (V20261215000100, kltn-docs 01 BR-03): only a carton is measured now.
 */
public enum RuleLevel {
    /** The unit the item marks as its default shipping unit. */
    DEFAULT_SHIPPING_UNIT,
    /** The largest active packing unit that has its full size and weight. */
    LARGEST_PACKING_UNIT
}
