package com.stockflow.production.api;

import com.stockflow.common.security.CurrentUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Production orders (LSX) for other modules, found by what people quote: the number on the job ticket, the sales order
 * they print for, or where they stand in the workshop. The back-office assistant (ADR-0010) answers "how far is SO-… in
 * print, and when is it due?" with it: the order module's own status says only IN_PRODUCTION until every production
 * order of the order is done (BR-PRD-06), and a hold here does not move it.
 *
 * <p><b>Scope.</b> Read through the production order list and detail the screens use, so the rules are those of
 * {@code GET /api/v1/production/orders}: the caller must be back-office staff, and a warehouse-bound caller sees only
 * the production orders of their warehouses (BR-SEC-002). Call it inside that endpoint's guard,
 * {@code @RequiresPermission(resource = "production-orders", action = READ)}; outside a guard no scope is in force and
 * every warehouse is seen, as by the system.</p>
 *
 * <p><b>What is left out.</b> The design snapshot, its checksum and the print files (the customer's artwork), a
 * subcontract's terms and price, and who did what: a view says where the work stands and what holds it, not what it
 * prints from, what it costs or which member of staff touched it.</p>
 */
public interface ProductionOrderQueries {

    /** The most production orders one call returns. */
    int MAX_ORDERS = 50;

    /** Every status, in the order work moves through them ({@code ck_production_order_status}, 03 §16). */
    List<String> STATUSES = List.of("PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "SUBCONTRACTED", "QC",
            "ON_HOLD", "COMPLETED", "CANCELLED");

    /** Work still to finish: every status but COMPLETED and CANCELLED, as the workshop dashboard counts it. */
    List<String> OPEN_STATUSES = List.of("PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "SUBCONTRACTED",
            "QC", "ON_HOLD");

    /** Every reason work is stopped for ({@code ck_production_order_hold_reason}). */
    List<String> HOLD_REASONS = List.of("FILE_REJECTED", "BLANKS_SHORT", "MACHINE_DOWN", "WAITING_CUSTOMER",
            "SUBCONTRACTOR_LATE", "OTHER");

    /**
     * One production order by its number, with its counts, its hold and its status history.
     *
     * @param orderNumber as printed ({@code LSX-20261028-0001}); case and surrounding blanks are ignored
     * @return empty when there is no such order, or none the caller's warehouses make - the same answer, so a
     *         warehouse-bound caller does not learn that another warehouse has it
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a blank number
     */
    Optional<ProductionOrderDetail> byNumber(String orderNumber, CurrentUser user);

    /**
     * The production orders that match, the earliest due date first (the print shop's queue order), then the oldest.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    Found find(Criteria criteria, CurrentUser user);

    /**
     * @param salesOrderId only the production orders of this sales order - one per printed line, and the children split
     *                     off them for a subcontractor or a shortfall; null for every order and every sample
     * @param statuses     any of {@link #STATUSES}; empty for every status
     * @param overdueOnly  only open ones due before today (Asia/Ho_Chi_Minh), as the workshop dashboard counts them;
     *                     a status given that is not open then matches nothing
     * @param limit        1..{@value #MAX_ORDERS}
     */
    record Criteria(UUID salesOrderId, List<String> statuses, boolean overdueOnly, int limit) {

        public Criteria {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }
    }

    /** @param total how many match; more than {@code orders} holds means some were not returned */
    record Found(List<ProductionOrderView> orders, long total) {

        public Found {
            orders = List.copyOf(orders);
        }
    }
}
