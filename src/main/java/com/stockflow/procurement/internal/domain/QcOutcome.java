package com.stockflow.procurement.internal.domain;

/** A QC decision on part of a receipt line (docs 03 §5.2, {@code ck_qc_inspections_outcome}). */
public enum QcOutcome {
    /** Passed; stays in the QC area as INBOUND until it is put away. */
    ACCEPTED,
    /** On hold in a QUARANTINE area, waiting for a second decision. */
    QUARANTINE,
    /** Failed; BLOCKED in a QUARANTINE area until it goes back to the supplier. */
    REJECTED
}
