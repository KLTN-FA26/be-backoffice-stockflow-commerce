package com.stockflow.procurement.internal.domain;

/** Lifecycle of a supplier invoice, incl. three-way-match outcome. Mapped {@code EnumType.STRING}. */
public enum InvoiceStatus { RECEIVED, MATCHED, DISPUTED, VOID, PAID }
