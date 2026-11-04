package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rows a lookup returned, as the panel shows them under the answer (design §7): exact, straight from the tool's
 * stored result, instead of retyped by the model.
 *
 * <p><b>Why the server builds them.</b> A small model retyping fifty rows of figures into Markdown spends output tokens
 * on it and swaps two numbers now and then; both numbers exist in the result, so no check on the answer catches the
 * swap. A table built here from the result the model saw cannot differ from it, and the answer above it shrinks to
 * what matters: the headline and what to act on (the system prompt says so).</p>
 *
 * <p><b>Read from the stored JSON, never from the tool classes.</b> A table is rebuilt every time a transcript is
 * read, from {@code assistant.tool_call.result}, as citations and proposals are ({@link AnswerSources}): the live
 * answer and the reopened conversation show the same table, and an answer stored before a tool changed still reads.
 * Only a result whose outcome is OK is a source; anything else carries no rows.</p>
 *
 * <p><b>Fixed columns per list.</b> Each list a person scans has its columns declared below, in the order of the
 * tool's record, keyed by the record's field names ({@code hold.reason} for a field of a nested record). The frontend
 * labels them. A field the tool adds later does not appear in the panel until it is declared here; a field absent
 * from a row (the tools leave nulls out) is absent from the row's map.</p>
 *
 * <p>An empty list makes no table: the answer says that nothing was found. Two calls of one tool make two tables
 * with the same {@code key}: the key names the list, for the frontend's label, not the table.</p>
 */
public final class ResultTable {

    private static final List<String> LOT_COLUMNS = List.of("sku", "warehouseCode", "lotNumber", "expiryDate",
            "daysToExpiry", "quantityAtLastCheck", "lastCheckedAt", "stillHeld");

    /** Every list the panel shows, by the tool that returns it. */
    private static final Map<String, List<Shape>> SHAPES = Map.ofEntries(
            Map.entry("findLowStock", List.of(
                    Shape.of("findLowStock.skus", "skus", "total", "truncated", List.of(),
                            "sku", "usableOnHand", "availableToPromise", "reorderPoint", "safetyStock",
                            "atOrBelowReorderPoint", "belowSafetyStock"))),
            Map.entry("findExpiringLots", List.of(
                    Shape.of("findExpiringLots.expiring", "expiring", "expiringTotal", "expiringTruncated",
                            List.of(), LOT_COLUMNS.toArray(String[]::new)),
                    Shape.of("findExpiringLots.expired", "expired", "expiredTotal", "expiredTruncated",
                            List.of(), LOT_COLUMNS.toArray(String[]::new)))),
            Map.entry("getStockLevels", List.of(
                    Shape.of("getStockLevels.warehouses", "warehouses", null, null, List.of("sku"),
                            "sku", "warehouseCode", "onHand", "inAvailableStatus", "heldOnStock",
                            "availableToPromise"))),
            Map.entry("getStockLots", List.of(
                    Shape.of("getStockLots.locations", "locations", "total", "truncated", List.of("sku"),
                            "sku", "locationCode", "lotNumber", "expiryDate", "onHand", "held", "unheld"))),
            Map.entry("findOrders", List.of(
                    Shape.of("findOrders.orders", "orders", "total", "truncated", List.of(),
                            "orderNumber", "status", "placedAt", "total", "currency", "paymentTerm",
                            "paymentStatus", "paidAmount", "depositRequired", "hold.reason", "hold.note",
                            "deliveryFailureReason", "cancellationReasonCode"))),
            Map.entry("findPurchaseOrders", List.of(
                    Shape.of("findPurchaseOrders.purchaseOrders", "purchaseOrders", "total", "truncated", List.of(),
                            "poNumber", "type", "supplierCode", "supplierName", "warehouseCode", "status",
                            "supplierConfirmationStatus", "orderDate", "expectedAt", "overdue", "daysLate",
                            "subtotal", "taxTotal", "totalIncludingTax", "currency", "rejectionReason"),
                    // The lines come with the one purchase order asked about: a list inside its row.
                    Shape.nested("findPurchaseOrders.lines", "purchaseOrders", "lines", "lineCount",
                            List.of("poNumber"),
                            "poNumber", "lineNo", "sku", "ordered", "received", "stillToReceive", "status",
                            "unitPriceBeforeTax"))),
            Map.entry("getSupplierTerms", List.of(
                    Shape.of("getSupplierTerms.terms", "terms", "total", "truncated", List.of(),
                            "sku", "supplierCode", "supplierName", "supplierStatus", "preferred",
                            "supplierSkuCode", "unitPriceBeforeTax", "currency", "moq", "packSize",
                            "leadTimeDays", "skuPurchasable", "orderable"))),
            Map.entry("findProductionOrders", List.of(
                    Shape.of("findProductionOrders.productionOrders", "productionOrders", "total", "truncated",
                            List.of(),
                            "productionOrderNumber", "type", "mode", "status", "holdReason", "warehouseCode",
                            "blankSku", "plannedUnits", "goodUnitsPrinted", "dueDate", "overdue",
                            "salesOrderNumber", "sampleRequestNumber", "sampleRound"))),
            Map.entry("getSalesSummary", List.of(
                    Shape.of("getSalesSummary.topSkus", "topSkus", null, null, List.of(),
                            "sku", "productName", "quantitySold", "revenue"))),
            Map.entry("findFulfillmentTasks", List.of(
                    Shape.of("findFulfillmentTasks.tasks", "tasks", "total", "truncated", List.of(),
                            "orderNumber", "warehouseCode", "pickStatus", "packStatus", "assigned", "assignedToUser",
                            "assignedAt", "startedAt", "pickedAt", "packedAt"))),
            Map.entry("findGoodsReceipts", List.of(
                    Shape.of("findGoodsReceipts.receipts", "receipts", "total", "truncated", List.of(),
                            "receiptNumber", "purchaseOrderNumber", "supplierName", "warehouseCode", "status",
                            "receivedAt", "confirmedAt", "closedAt"))));

    private ResultTable() {
    }

    /**
     * One list of rows as the panel shows it.
     *
     * @param key       the list it is, {@code tool.field} ({@code findExpiringLots.expired}): what the frontend labels
     *                  it by; repeated when the tool ran twice
     * @param tool      the tool that returned it
     * @param columns   the keys of {@code rows}, in display order: the tool record's field names
     * @param rows      the rows as returned, each a map from column to value (text, number or true/false); a value
     *                  the tool did not return is absent. Text may be what a person typed (a product name, a hold
     *                  note): the panel shows it as text, never as markup
     * @param total     how many rows there are in all, as the tool counted them; the number of rows when the list is
     *                  never cut
     * @param truncated more rows exist than are listed
     */
    public record Table(String key, String tool, List<String> columns, List<Map<String, Object>> rows, long total,
                        boolean truncated) {

        public Table {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(tool, "tool");
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /** The tools whose results the panel shows as tables, sorted: what the system prompt names. */
    public static Set<String> tools() {
        return Collections.unmodifiableSet(new TreeSet<>(SHAPES.keySet()));
    }

    /** Every table the OK results among {@code executions} make, in the order the tools ran. */
    public static List<Table> of(List<ToolExecution> executions, ObjectMapper json) {
        List<Table> tables = new ArrayList<>();
        for (ToolExecution execution : executions) {
            tables.addAll(of(execution, json));
        }
        return List.copyOf(tables);
    }

    /** The tables one tool call makes: none unless it ended OK and its tool returns a list the panel shows. */
    public static List<Table> of(ToolExecution execution, ObjectMapper json) {
        if (execution.outcome() != ToolOutcome.OK) {
            return List.of();
        }
        return of(execution.call().name(), execution.result(), json);
    }

    /**
     * The tables {@code result} makes, read as {@code tool}'s answer.
     *
     * @param result the tool's result as stored: its JSON; anything that is not JSON makes no table
     */
    public static List<Table> of(String tool, String result, ObjectMapper json) {
        List<Shape> shapes = SHAPES.get(tool);
        if (shapes == null || result == null) {
            return List.of();
        }
        JsonNode root;
        try {
            // Exact decimals, as the tool wrote them: 12000000.00 read as a double, or with its trailing zeros
            // stripped as tree nodes are by default, would reach the panel as 1.2E+7.
            root = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(result);
        } catch (JsonProcessingException notJson) {
            return List.of();
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        List<Table> tables = new ArrayList<>();
        for (Shape shape : shapes) {
            if (shape.container() == null) {
                shape.table(tool, root).ifPresent(tables::add);
            } else {
                root.path(shape.container()).forEach(container -> shape.table(tool, container)
                        .ifPresent(tables::add));
            }
        }
        return List.copyOf(tables);
    }

    /**
     * Where one list sits in a tool's result and which of its fields the panel shows.
     *
     * @param container the field of the result holding records that each hold the list; null when the result itself
     *                  holds it
     * @param list      the field holding the rows
     * @param total     the field counting every row; null when the list is never cut
     * @param truncated the field saying rows were left out; null to read it off {@code total}
     * @param carried   fields of the record holding the list copied into each row: the SKU a stock list is about,
     *                  the purchase order a line belongs to
     * @param columns   the columns, a dotted path for a field of a nested record
     */
    private record Shape(String key, String container, String list, String total, String truncated,
                         List<String> carried, List<String> columns) {

        static Shape of(String key, String list, String total, String truncated, List<String> carried,
                        String... columns) {
            return new Shape(key, null, list, total, truncated, carried, List.of(columns));
        }

        static Shape nested(String key, String container, String list, String total, List<String> carried,
                            String... columns) {
            return new Shape(key, container, list, total, null, carried, List.of(columns));
        }

        Optional<Table> table(String tool, JsonNode holder) {
            JsonNode items = holder.path(list);
            if (!items.isArray() || items.isEmpty()) {
                return Optional.empty();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    JsonNode value = carried.contains(column) && !item.has(column) ? holder.path(column)
                            : at(item, column);
                    Object cell = cell(value);
                    if (cell != null) {
                        row.put(column, cell);
                    }
                }
                rows.add(Collections.unmodifiableMap(row));
            }
            JsonNode counted = total == null ? null : holder.path(total);
            long all = counted != null && counted.canConvertToLong() ? Math.max(counted.longValue(), rows.size())
                    : rows.size();
            boolean cut = truncated == null ? all > rows.size() : holder.path(truncated).asBoolean(false);
            return Optional.of(new Table(key, tool, columns, rows, all, cut));
        }

        private static JsonNode at(JsonNode item, String path) {
            JsonNode node = item;
            for (String field : path.split("\\.")) {
                node = node.path(field);
            }
            return node;
        }

        /** A scalar as its Java value; null for anything absent, null or not a single value. */
        private static Object cell(JsonNode value) {
            if (value == null || value.isMissingNode() || value.isNull() || value.isContainerNode()) {
                return null;
            }
            if (value.isTextual()) {
                return value.textValue();
            }
            if (value.isBoolean()) {
                return value.booleanValue();
            }
            if (value.isIntegralNumber()) {
                return value.canConvertToLong() ? (Object) value.longValue() : value.decimalValue();
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
            return value.asText();
        }
    }
}
