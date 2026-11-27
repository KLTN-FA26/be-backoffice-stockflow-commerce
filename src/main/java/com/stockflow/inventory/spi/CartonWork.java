package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the module that picks and checks cartons which of them it is working on, for the carton list (#454; kltn-docs
 * 20 BR-10: a carton in open work is busy): a pick task still to pick it or holding it in staging, or a carton check
 * not completed. Implemented by {@code fulfillment}, which depends on {@code inventory :: api}; see the package
 * documentation for why the interface lives here.
 */
public interface CartonWork {

    /**
     * Runs in the caller's transaction. One question for a whole page, never one per carton.
     *
     * @param lpns normalised LPNs (upper case, trimmed)
     * @return those of {@code lpns} in open work, mapped to it; a carton in none is absent
     */
    Map<String, Work> openWorkOf(Collection<String> lpns);

    /** What kind of work. */
    enum Kind { PICK, CARTON_CHECK }

    /** The open work, by the pick task it belongs to - the task a screen opens. */
    record Work(Kind kind, UUID taskId) {
    }
}
