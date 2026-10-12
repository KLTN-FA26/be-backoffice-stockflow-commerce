package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Part of an ORDER printed by an outside print shop (SCRUM-433/441; 03 §16; 04c F-PROD "Nhánh gia công ngoài").
 */
public interface Subcontracting {

    /**
     * @param quantity     units taken from the parent's unissued quantity (BR-PRD-12)
     * @param subcontractorId a supplier flagged as a print subcontractor (BR-PRD-13)
     * @param unitPrice    the price of the print work per good unit
     * @param dueDate      when the subcontractor delivers
     */
    record Split(int quantity, UUID subcontractorId, ProductionOrder.SubcontractMethod method, BigDecimal unitPrice,
                 String currency, LocalDate dueDate) {
    }

    /**
     * G1–G3: the parent keeps its status and loses {@code quantity}; the child (READY if the parent passed
     * prepress, else PENDING_PREPRESS) gets its DRAFT SUBCONTRACT PO; the order's blanks for that quantity are
     * let go (FULL_SERVICE) or held for the child instead (SUPPLIED_BLANKS) in the same transaction.
     *
     * @return the child
     */
    ProductionOrders.Detail split(CurrentUser user, UUID parentId, Split split);

    /** G6: READY → SUBCONTRACTED, only once the child's SUBCONTRACT PO is approved (BR-PRD-13). */
    ProductionOrders.Detail sendOut(CurrentUser user, UUID childId);
}
