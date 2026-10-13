package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>production</b> module when work on a production order stops (SCRUM-396/401): a
 * rejected print file, blanks short, a machine down, waiting for the customer. Sales acts on a rejected
 * file or a customer wait (Jira 396: "Sales is notified to handle a design change"), purchasing on
 * blanks short (Jira 398), the Warehouse Manager on the rest.
 *
 * <p>Facts and codes only (verify.py rule 16, "contracts carry facts, not free text"): {@code reasonCode} is the
 * hold reason ({@code FILE_REJECTED}, {@code BLANKS_SHORT}, {@code MACHINE_DOWN}, {@code WAITING_CUSTOMER},
 * {@code SUBCONTRACTOR_LATE}, {@code OTHER}); what the person who held it typed stays on the production order, behind
 * its permission, and a reader opens the order to see it. Exactly one of {@code sampleRequestId} and
 * {@code salesOrderId} is set, as the production order's type says. {@code warehouseId} is the production order's
 * warehouse: the Warehouse Manager notice goes to the managers who can see it. {@code blankSku} is what the order
 * prints on; {@code blanksNeeded} how many more blanks it waits for when it is held BLANKS_SHORT and the count is
 * known, null otherwise. {@code heldAt} is when it was held: one hold is one in-app notice, however often the
 * event is delivered.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made durable by
 * Spring Modulith's event publication registry. See {@code package-info.java} for the rules on changing
 * this contract.</p>
 */
public record ProductionOrderHeld(
        UUID productionOrderId,
        String orderNumber,
        String reasonCode,
        UUID sampleRequestId,
        UUID salesOrderId,
        UUID warehouseId,
        String blankSku,
        Integer blanksNeeded,
        java.time.Instant heldAt
) {
}
