package com.stockflow.contracts;

/** Outcome of an inbound quality inspection (BRD 3.3.4). */
public enum QcResult {
    ACCEPTED,
    REJECTED,
    QUARANTINED
}
