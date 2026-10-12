package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>production</b> module when work on a production order stops (SCRUM-396/401): a
 * rejected print file, blanks short, a machine down, waiting for the customer. Sales acts on a rejected
 * file or a customer wait (Jira 396: "Sales is notified to handle a design change"), purchasing on
 * blanks short (Jira 398).
 *
 * <p>{@code reason} is the hold reason name ({@code FILE_REJECTED}, {@code BLANKS_SHORT},
 * {@code MACHINE_DOWN}, {@code WAITING_CUSTOMER}, {@code SUBCONTRACTOR_LATE}, {@code OTHER}). Exactly one
 * of {@code sampleRequestId} and {@code salesOrderId} is set, as the production order's type says.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made durable by
 * Spring Modulith's event publication registry. See {@code package-info.java} for the rules on changing
 * this contract.</p>
 */
public record ProductionOrderHeld(
        UUID productionOrderId,
        String orderNumber,
        String reason,
        String note,
        UUID sampleRequestId,
        UUID salesOrderId
) {
}
