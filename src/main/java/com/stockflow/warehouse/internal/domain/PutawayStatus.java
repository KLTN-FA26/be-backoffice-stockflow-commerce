package com.stockflow.warehouse.internal.domain;

/** Lifecycle of a putaway task. Mapped {@code EnumType.STRING}. */
public enum PutawayStatus { PENDING, IN_PROGRESS, DONE, CANCELLED }
