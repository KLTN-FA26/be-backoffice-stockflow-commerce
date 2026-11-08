package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a pack. Mapped {@code EnumType.STRING}. There is no hold for a design check any more: printed goods
 * arrive carrying the customer's design (kltn-docs 08 §1, V20261108000500).
 */
public enum PackStatus { PENDING, PACKING, PACKED, CANCELLED }
