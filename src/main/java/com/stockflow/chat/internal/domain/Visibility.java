package com.stockflow.chat.internal.domain;

/**
 * Who sees a message: both sides ({@code CUSTOMER}), or the staff alone ({@code STAFF}: an internal note, 16 BR-07, or
 * a system line meant for the staff). Mapped {@code EnumType.STRING}.
 */
public enum Visibility { CUSTOMER, STAFF }
