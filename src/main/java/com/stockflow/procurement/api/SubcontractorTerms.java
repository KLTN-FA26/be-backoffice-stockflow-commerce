package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param lossTolerancePercent the blanks loss this subcontractor may have before it is charged to them
 *                             (BR-17; default 2 %, open question B14)
 */
public record SubcontractorTerms(
        UUID supplierId,
        String code,
        String name,
        boolean printSubcontractor,
        BigDecimal lossTolerancePercent
) {
}
