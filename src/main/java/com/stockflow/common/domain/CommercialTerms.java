package com.stockflow.common.domain;
/** Application defaults. SQL migration defaults are immutable historical snapshots, verified by tests. */
public final class CommercialTerms {
    public static final int PAYMENT_DAYS = 30;
    public static final int LEAD_DAYS = 7;
    private CommercialTerms() {}
}
