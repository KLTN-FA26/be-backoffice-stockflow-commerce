package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a pick. SHORT = fewer units found than required; CANCELLED = its order was cancelled
 * (SCRUM-460). Mapped {@code EnumType.STRING}.
 */
public enum PickStatus { PENDING, PICKING, PICKED, SHORT, CANCELLED }
