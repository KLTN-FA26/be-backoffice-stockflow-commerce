package com.stockflow.fulfillment.internal.domain;

/**
 * What the goods are packed in (kltn-docs 08 step 3-4: carton, bag, ...). Load planning (09 §4.2) reads the measures,
 * not the type; the type is for the label and the carrier. Mapped {@code EnumType.STRING}.
 */
public enum PackageType { CARTON, BAG, PALLET, CRATE, OTHER }
