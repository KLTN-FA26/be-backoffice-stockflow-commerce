package com.stockflow.inventory.api;

/**
 * Which level of the packaging rule chain produced a {@link PackagingSpec} (SCRUM-287, decision LP2-2), in the
 * order the resolver tries them. The item's box and the item's unit were levels too, until the item lost its own
 * measures (V20261215000100, kltn-docs 01 BR-03): only a carton is measured now.
 */
public enum RuleLevel {
    /**
     * The item's one active carton (01 BR-03). The name is kept from the default shipping mark it replaced
     * (V20261226000300): clients read it.
     */
    DEFAULT_SHIPPING_UNIT,
    /**
     * The largest active packing unit that has its full size and weight, when an item has several active units -
     * which {@code uk_uom_conversions_one_carton} refuses, so not produced from the database's data.
     */
    LARGEST_PACKING_UNIT
}
