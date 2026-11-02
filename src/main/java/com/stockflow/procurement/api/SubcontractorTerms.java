package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param active               whether the supplier takes new orders ({@code ACTIVE}); a split may only go to an active
 *                             subcontractor (03 §16, BR-PRD-13)
 * @param lossTolerancePercent the blanks loss this subcontractor may have before it is charged to them
 *                             (BR-17; default 2 %, open question B14)
 */
public record SubcontractorTerms(
        UUID supplierId,
        String code,
        String name,
        boolean printSubcontractor,
        boolean active,
        BigDecimal lossTolerancePercent
) {
}
