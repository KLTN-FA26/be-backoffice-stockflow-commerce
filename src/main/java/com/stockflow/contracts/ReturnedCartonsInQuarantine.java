package com.stockflow.contracts;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>inventory</b> module once a day per warehouse (WF-05 BE-24; decision TH-6): {@code cartons}
 * cartons that came back on a return receipt are in its quarantine, waiting for a warehouse manager to release them,
 * write them off, or give them back to the customer. Nothing else reminds anyone of them, and a carton left there is
 * stock nobody sells and nobody writes off.
 *
 * <p>Keyed by warehouse and {@code day}: a consumer that sees it twice tells nobody twice. Consumers receive it
 * in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 */
public record ReturnedCartonsInQuarantine(UUID warehouseId, String warehouseCode, int cartons, LocalDate day) {
}
