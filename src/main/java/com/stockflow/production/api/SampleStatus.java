package com.stockflow.production.api;

public enum SampleStatus {
    DRAFT, SUBMITTED, IN_PRODUCTION, READY, SENT_TO_CUSTOMER,
    CHANGES_REQUESTED, APPROVED, CANCELLED;

    public boolean canTransitionTo(SampleStatus target) {
        return switch (this) {
            case DRAFT -> target == SUBMITTED || target == CANCELLED;
            case SUBMITTED -> target == IN_PRODUCTION;
            case IN_PRODUCTION -> target == READY;
            case READY -> target == SENT_TO_CUSTOMER;
            case SENT_TO_CUSTOMER -> target == APPROVED || target == CHANGES_REQUESTED;
            case CHANGES_REQUESTED -> target == DRAFT;
            case APPROVED, CANCELLED -> false;
        };
    }
}
