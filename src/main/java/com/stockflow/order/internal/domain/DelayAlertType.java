package com.stockflow.order.internal.domain;

/**
 * The four delay alerts of the production schedule (4A-7, kltn-docs 17 §4.12 step 48, BR-30). Two are about a
 * production order, two about a delivery batch - also {@code ck_delay_alert_subject}.
 */
public enum DelayAlertType {

    /** The producer's expected completion day is after the day the goods are needed. */
    AT_RISK(Subject.PRODUCTION_ORDER),

    /** The need-by day has passed and the production order has not been received in full. */
    PRODUCTION_LATE(Subject.PRODUCTION_ORDER),

    /** A batch still lacks cartons no open production order brings, and its window starts within the lead time. */
    PO_NEEDED(Subject.DELIVERY_BATCH),

    /** A batch's window has ended and it has not all been handed over. */
    DELIVERY_LATE(Subject.DELIVERY_BATCH);

    /** What an alert is about. */
    public enum Subject { PRODUCTION_ORDER, DELIVERY_BATCH }

    private final Subject subject;

    DelayAlertType(Subject subject) {
        this.subject = subject;
    }

    public Subject subject() {
        return subject;
    }

    /**
     * BR-30: who is told. DELIVERY_LATE is for those who deliver - Sales, the Sales Manager and the shipping warehouse's
     * manager, not the Production Coordinator; the other three are for those who follow production.
     */
    public boolean aboutDelivery() {
        return this == DELIVERY_LATE;
    }
}
