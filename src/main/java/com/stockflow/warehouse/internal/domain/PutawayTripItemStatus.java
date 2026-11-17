package com.stockflow.warehouse.internal.domain;

/**
 * Where a carton of a putaway trip stands: {@code ON_CART}, {@code PLACED} in a bin (its task is DONE), or
 * {@code RETURNED} - taken off the cart before it was put away, its task PENDING again for anybody to take.
 */
public enum PutawayTripItemStatus { ON_CART, PLACED, RETURNED }
