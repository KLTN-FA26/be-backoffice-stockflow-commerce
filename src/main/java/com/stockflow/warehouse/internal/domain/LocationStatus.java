package com.stockflow.warehouse.internal.domain;

/**
 * Operating state of a shelf, a bin or an area. Mapped {@code EnumType.STRING}.
 *
 * <p>{@code INACTIVE} is how a location leaves the layout: nothing is ever hard-deleted, because a
 * location code may still appear in stock and movement history. "Full" is deliberately not a
 * state - it is derived from stock against capacity, never stored (docs module 06 §5).</p>
 */
public enum LocationStatus { ACTIVE, BLOCKED, MAINTENANCE, INACTIVE }
