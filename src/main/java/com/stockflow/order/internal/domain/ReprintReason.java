package com.stockflow.order.internal.domain;

/**
 * Why a print sample was printed again (kltn-docs 17 §4.3 step 9, BR-24), classified by the Production Coordinator when
 * they open the next round - the customer only writes what to change (2026-11-05, 2026-11-07). The reason decides who
 * pays for the round: a defect is the producer's, so it is not counted against the contract's free sample rounds
 * (14 BR-18).
 */
public enum ReprintReason {
    /** The producer printed it wrong. Not counted. */
    PRINT_DEFECT,
    /** The customer changed their mind - colour, position, a detail - on the same print file. Counted. */
    CUSTOMER_CHANGE;

    /** Whether a round printed again for this reason counts against the contract's free sample rounds. */
    public boolean counted() {
        return this == CUSTOMER_CHANGE;
    }
}
