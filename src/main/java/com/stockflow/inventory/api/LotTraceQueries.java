package com.stockflow.inventory.api;

import java.util.List;

/**
 * The lot traceability report (SCRUM-153) for other modules, looked up by the lot number people read off a carton.
 *
 * <p>The back office's lot screen asks by id, which nobody outside that screen knows; the assistant (ADR-0010) is
 * asked "where did lot L-2310 go?". This is the same report, reached by the number instead.</p>
 *
 * <p><b>Scope.</b> The caller's warehouse limit (BR-SEC-002) is applied exactly as on
 * {@code GET /api/v1/inventory/lots}: a warehouse-bound caller sees only lots their warehouses held, and only the
 * balances, holds and orders inside them. Call it inside the same guard as that endpoint,
 * {@code @RequiresPermission(resource = "inventory-stock-movements", action = READ, scope = WAREHOUSE)}; outside a
 * guard no scope is in force and the report treats the caller as the system.</p>
 */
public interface LotTraceQueries {

    /** The most lots one lookup traces; two receipts may share a number, a dozen never do. */
    int MAX_LOTS = 10;

    /**
     * Every lot printed with {@code lotNumber} that the caller may see, most recently received first, each traced.
     *
     * @param lotNumber exact lot number as printed; surrounding blanks are ignored
     * @param limit     1..{@value #MAX_LOTS}
     * @return empty when there is no such lot, or none the caller's warehouses held
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a blank number or a limit
     *         out of range
     */
    List<LotTraceView> traceLot(String lotNumber, int limit);
}
