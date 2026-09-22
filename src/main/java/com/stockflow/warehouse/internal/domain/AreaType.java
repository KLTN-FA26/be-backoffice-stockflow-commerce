package com.stockflow.warehouse.internal.domain;

/**
 * What a floor area is for. Mapped {@code EnumType.STRING}.
 *
 * <p>Every type except {@code NON_STORAGE} can hold stock (docs module 06 BR-11); an office or a
 * charging bay is on the map only so it can be drawn and walked around.</p>
 */
public enum AreaType { RECEIVING, QUARANTINE, PACKING, DISPATCH, OVERFLOW, NON_STORAGE }
