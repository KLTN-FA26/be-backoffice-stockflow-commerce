package com.stockflow.fulfillment.internal.domain;

/**
 * Why a load plan, or one of its vehicles, was called off after loading started (SCRUM-474 UNLOAD-DESIGN point 7;
 * kltn-docs 09 BR-14). Always stored with a free-text note. Mapped {@code EnumType.STRING}; the same four codes are
 * a {@code CHECK} on {@code dispatch_load}, {@code load_plan} and {@code dispatch_load_unload}.
 */
public enum UnloadReasonCode {
    /** The vehicle broke down. */
    XE_HONG,
    /** The wrong vehicle came (type, size or plate not the one booked). */
    SAI_XE,
    /** A parcel does not fit the place the plan gave it. */
    KIEN_KHONG_VUA,
    /** Anything else; the note says what. */
    KHAC
}
