package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.api.ShipmentQueries;
import com.stockflow.fulfillment.api.ShipmentSummary;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shipments as dispatch sees them - "chuyến nào đang giao thất bại", "chuyến giao của SO-… đã giao chưa", "còn bao
 * nhiêu chuyến chờ xuất" - for the warehouse and the order coordinator (P8).
 *
 * <p><b>The screen's list.</b> {@code GET /api/v1/fulfillment/shipments}, whose guard this tool carries, read through
 * {@code fulfillment :: api}: the same rows, newest first, the same warehouse limit. A warehouse is named by its code,
 * looked up once per warehouse as {@link FindFulfillmentTasksTool} does.</p>
 *
 * <p><b>Queues from their head.</b> Waiting for dispatch, with the carrier and failed are work still to do, listed
 * the longest waiting first, so a queue longer than one result loses its newest rows rather than its oldest; any
 * other filter lists the most recently ready first, as the screen does. A status is the shipment's now, whenever it
 * got there: there is no date filter, and the description says so.</p>
 *
 * <p><b>Thirty rows, cut names.</b> A shipment row of the longest values (a 48-character code, a carrier name cut to
 * {@value #CARRIER_LENGTH} characters, a load code, a COD amount and four instants) is about 600 characters, so
 * {@value #MAX_ROWS} rows stay under the result limit.</p>
 *
 * <p><b>No tracking number, on purpose.</b> No carrier is called (fulfillment's design, 09 §1), so there is none to
 * give; the description says so, rather than let a model make one up from the shipment code. A failure reason stays on
 * a shipment delivered or returned after a retry, so the description says what it means then. The staff note beside
 * it is on the shipment's detail, not here.</p>
 */
@Component
class FindShipmentsTool implements AssistantTool {

    static final int MAX_ROWS = 30;
    static final int CARRIER_LENGTH = 40;

    /** fulfillment's {@code ShipmentStatus}, plus ANY. */
    static final List<String> STATUSES = List.of("ANY", "READY_TO_DISPATCH", "HANDED_OVER", "DELIVERED",
            "DELIVERY_FAILED", "RETURNED", "CANCELLED");

    /** The statuses that are work still to do, listed the longest waiting first. */
    static final java.util.Set<String> QUEUES = java.util.Set.of("READY_TO_DISPATCH", "HANDED_OVER",
            "DELIVERY_FAILED");

    private static final ToolSpec SPEC = new ToolSpec("findShipments",
            "Shipments of customer orders: shipment code (the order number and -S with a number), order number, "
                    + "warehouse, status, delivery mode (PARCEL or FULL_TRUCKLOAD), packageCount (parcels), the "
                    + "carrier, the load it is on, partial (part of an order sent while the rest waits), codAmount "
                    + "(what the carrier collects on delivery, in currency; absent when nothing), and when it was "
                    + "ready, handed to the carrier and when the outcome was recorded. status is READY_TO_DISPATCH "
                    + "(packed, waiting for the carrier), HANDED_OVER (with the carrier), DELIVERED, DELIVERY_FAILED "
                    + "(not delivered; it may still be delivered or come back), RETURNED (back at the warehouse) or "
                    + "CANCELLED, as it is now, whenever it got there: there is no date filter, so never call a list "
                    + "today's. failureReason (CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER) is "
                    + "why the last failed attempt failed: on a DELIVERED or RETURNED shipment it is an earlier "
                    + "attempt's, not the outcome. Under READY_TO_DISPATCH, HANDED_OVER or DELIVERY_FAILED the longest "
                    + "waiting come first; otherwise the most recently ready. Count parcels with packageCount, not "
                    + "rows. There are no tracking numbers: no carrier system is connected, so never give one or "
                    + "invent one. Filter by status and by text in the shipment code, such as an order number. When "
                    + "total is above the shipments returned, say how many were left out. A user limited to some "
                    + "warehouses sees only theirs.",
            ToolSchema.object()
                    .choice("status", "Only shipments in this status; ANY for every status", STATUSES)
                    .string("search", "Text to match in the shipment code, such as an order number "
                            + "(SO-yyyyMMdd-nnnnnn); an empty string for no text filter")
                    .integer("limit", "How many shipments to return, 1 to " + MAX_ROWS)
                    .build());

    private final ShipmentQueries shipments;
    private final WarehouseService warehouses;

    FindShipmentsTool(ShipmentQueries shipments, WarehouseService warehouses) {
        this.shipments = shipments;
        this.warehouses = warehouses;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/fulfillment/shipments";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.SHIPMENTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String status = arguments.choice("status", STATUSES);
        String search = arguments.optionalText("search", 64);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        PageResponse<ShipmentSummary> page = shipments.shipments("ANY".equals(status) ? null : status, search,
                QUEUES.contains(status), 0, limit);
        Map<UUID, Optional<WarehouseView>> sites = new HashMap<>();
        List<Row> rows = page.items().stream().map(shipment -> Row.of(shipment, sites
                        .computeIfAbsent(shipment.warehouseId(), warehouses::findWarehouse)
                        .map(WarehouseView::prefix).orElse(null)))
                .toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size());
    }

    /**
     * @param total     every match within the user's warehouses; {@code shipments} holds the first {@code limit}
     * @param truncated {@code total} is above the shipments returned
     */
    record Answer(List<Row> shipments, long total, boolean truncated) {
    }

    /**
     * @param carrierName   cut to {@value FindShipmentsTool#CARRIER_LENGTH} characters
     * @param codAmount     what the carrier collects on delivery; null when nothing
     * @param failureReason why the last failed attempt failed; on DELIVERED or RETURNED an earlier attempt's
     * @param outcomeAt     when delivered, not delivered or back at the warehouse was recorded
     */
    record Row(String shipmentCode, String orderNumber, String warehouseCode, String status, String deliveryMode,
               int packageCount, String carrierName, String loadCode, boolean partial, BigDecimal codAmount,
               String currency, Instant readyAt, Instant handedOverAt, Instant outcomeAt, String failureReason) {

        static Row of(ShipmentSummary shipment, String warehouseCode) {
            boolean cod = shipment.codAmount() != null && shipment.codAmount().signum() > 0;
            return new Row(shipment.code(), shipment.orderNumber(), warehouseCode, shipment.status(),
                    shipment.deliveryMode(), shipment.packageCount(), cut(shipment.carrierName()),
                    shipment.loadCode(), shipment.partial(), cod ? shipment.codAmount() : null,
                    cod ? shipment.currency() : null, shipment.readyAt(), shipment.handedOverAt(),
                    shipment.outcomeAt(), shipment.failureReason());
        }

        private static String cut(String name) {
            if (name == null || name.length() <= CARRIER_LENGTH) {
                return name;
            }
            int end = Character.isHighSurrogate(name.charAt(CARRIER_LENGTH - 1)) ? CARRIER_LENGTH - 1
                    : CARRIER_LENGTH;
            return name.substring(0, end).stripTrailing() + "…";
        }
    }
}
