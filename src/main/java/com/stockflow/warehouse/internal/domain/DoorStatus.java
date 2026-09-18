package com.stockflow.warehouse.internal.domain;

/**
 * Whether a door can be walked through right now. Mapped {@code EnumType.STRING}.
 *
 * <p>Only doors have one; a wall never does (enforced by {@code ck_boundary_kind}).</p>
 */
public enum DoorStatus { OPEN, CLOSED }
