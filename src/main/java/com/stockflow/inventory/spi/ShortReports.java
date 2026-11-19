package com.stockflow.inventory.spi;

import java.util.Optional;

/**
 * The reason a picker gave when a carton could not be taken (kltn-docs 07 §4.4 step 9): it was not found where the books
 * say, or it was damaged. Recorded on the pick line; asked here by the carton, because the line it stood on may have
 * been reallocated since. Inventory asks before it lets a manager quarantine a carton that a short report holds on its
 * blind count: only a damaged carton is physically there to be quarantined (WF04 G7).
 */
public interface ShortReports {

    /** Why a carton can be missing from a pick line. */
    enum Reason { NOT_FOUND, DAMAGED }

    /** The reason of the latest short report of the carton {@code lpn}; empty when it was never reported short. */
    Optional<Reason> latestReasonOf(String lpn);
}
