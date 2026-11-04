package com.stockflow.reporting.internal.domain;

/** The two order events the sales rollups apply. The names are the values of {@code processed_order_events.event_type}. */
public enum OrderEventType {
    PLACED,
    CANCELLED
}
