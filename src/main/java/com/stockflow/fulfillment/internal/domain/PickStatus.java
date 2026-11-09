package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a pick. CANCELLED = its order was cancelled (SCRUM-460). A short pick is a line's state
 * ({@link PickLineStatus#SHORT}), never the task's: the task stays PICKING while the coordinator reallocates, so the
 * former SHORT, which nothing wrote, is gone (V20261113000400). Mapped {@code EnumType.STRING}.
 */
public enum PickStatus { PENDING, PICKING, PICKED, CANCELLED }
