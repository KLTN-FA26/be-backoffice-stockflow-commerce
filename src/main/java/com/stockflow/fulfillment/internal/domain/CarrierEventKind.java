package com.stockflow.fulfillment.internal.domain;

/**
 * What a carrier's webhook reported, in the system's terms (kltn-docs 09 §4.5 step 7 and its table for carriers booked
 * through an API). The carrier's own statuses are read into these by its reader; the applier decides from these alone.
 */
public enum CarrierEventKind {

    /** The waybill number, and the vehicle and driver with it (a WEBHOOK_LOAD carrier, 09 §4.4 source 1). */
    WAYBILL(Level.LOAD),
    /** A driver took the order (Ahamove {@code ACCEPTED}): the driver's name and phone go on the load. */
    DRIVER_ASSIGNED(Level.LOAD),
    /** The driver gave the order back and the carrier looks for another (Ahamove {@code ACCEPTED → ASSIGNING}). */
    DRIVER_RELEASED(Level.LOAD),
    /** The goods were picked up at the warehouse: a milestone only. */
    PICKED_UP(Level.LOAD),
    /** On the way to the drop-offs: a milestone only. */
    IN_TRANSIT(Level.LOAD),
    /** Delivered to the recipient (a shipment; for a WEBHOOK_LOAD carrier, the whole load when it names none). */
    DELIVERED(Level.SHIPMENT),
    /** Not delivered, with the carrier's reason (same reach as {@link #DELIVERED}). */
    DELIVERY_FAILED(Level.SHIPMENT),
    /** The goods are on their way back to the warehouse: a milestone only. */
    RETURNING(Level.LOAD),
    /** The goods are back at the warehouse. */
    RETURNED(Level.SHIPMENT),
    /** The carrier cancelled the order before the pickup (an API carrier). */
    ORDER_CANCELLED(Level.LOAD),
    /**
     * The order cancelled at the system's own request (Ahamove {@code cancel_by_user}), echoed back: the booking is
     * cancelled here by whoever asked, so nothing follows from the echo.
     */
    CANCELLED_ON_REQUEST(Level.LOAD),
    /** The carrier closed the order; the outcomes of its drop-offs it carries are applied if not yet. */
    ORDER_COMPLETED(Level.LOAD),
    /** A status the mapping has no use for. */
    OTHER(Level.LOAD);

    /** Whether the report is about the load as a whole or may name one shipment on it. */
    public enum Level { LOAD, SHIPMENT }

    private final Level level;

    CarrierEventKind(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }

    /** A milestone of the journey (09 §4.5 step 9), recorded once per load. */
    public boolean milestone() {
        return this == PICKED_UP || this == IN_TRANSIT || this == RETURNING;
    }
}
