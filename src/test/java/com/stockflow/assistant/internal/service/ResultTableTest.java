package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tables under an answer (design §7): built from the tool's stored result, with the tool record's own field names
 * as columns, so the panel shows exactly what the model was given.
 */
class ResultTableTest {

    /** Every tool whose rows the panel shows, with the record its {@code run} returns. */
    private static final Map<String, Class<?>> ANSWERS = Map.ofEntries(
            Map.entry("findLowStock", FindLowStockTool.Answer.class),
            Map.entry("findExpiringLots", FindExpiringLotsTool.Answer.class),
            Map.entry("getStockLevels", GetStockLevelsTool.Answer.class),
            Map.entry("getStockLots", GetStockLotsTool.Answer.class),
            Map.entry("findOrders", FindOrdersTool.Answer.class),
            Map.entry("findPurchaseOrders", FindPurchaseOrdersTool.Answer.class),
            Map.entry("getSupplierTerms", GetSupplierTermsTool.Answer.class),
            Map.entry("findProductionOrders", FindProductionOrdersTool.Answer.class),
            Map.entry("getSalesSummary", GetSalesSummaryTool.Answer.class),
            Map.entry("findFulfillmentTasks", FindFulfillmentTasksTool.Answer.class),
            Map.entry("findGoodsReceipts", FindGoodsReceiptsTool.Answer.class),
            Map.entry("findPendingAdjustments", FindPendingAdjustmentsTool.Answer.class),
            Map.entry("findShipments", FindShipmentsTool.Answer.class),
            Map.entry("findOverdueCustomers", FindOverdueCustomersTool.Answer.class));

    /** What Spring Boot builds from {@code application.yml}: java.time as ISO text, null fields left out. */
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    @Test
    @DisplayName("every column is a field of the tool's own record, so a renamed field fails here and not in the "
            + "panel: each tool's record, every field set, fills every column of its tables")
    void columnsAreTheRecordsFields() throws Exception {
        assertThat(ANSWERS.keySet()).as("a tool that gets a table is listed here with its record")
                .containsExactlyInAnyOrderElementsOf(ResultTable.tools());
        for (Map.Entry<String, Class<?>> tool : ANSWERS.entrySet()) {
            ToolExecution execution = stored(tool.getKey(), sample(tool.getValue()));

            List<ResultTable.Table> tables = ResultTable.of(execution, json);

            assertThat(tables).as("the tables of %s", tool.getKey())
                    .hasSize(List.of("findExpiringLots", "findPurchaseOrders").contains(tool.getKey()) ? 2 : 1);
            for (ResultTable.Table table : tables) {
                assertThat(table.tool()).isEqualTo(tool.getKey());
                assertThat(table.key()).startsWith(tool.getKey() + ".");
                assertThat(table.rows()).singleElement().satisfies(row -> assertThat(row.keySet())
                        .as("the columns of %s found in %s's result", table.key(), tool.getKey())
                        .containsExactlyElementsOf(table.columns()));
            }
        }
    }

    @Test
    @DisplayName("low stock: the rows as returned, a field the tool left out absent from its row, the tool's own "
            + "total and truncated")
    void lowStock() {
        ToolExecution execution = ok("findLowStock", """
                {"total":23,"truncated":true,"skus":[
                  {"sku":"EVAL-CUP-12OZ","usableOnHand":40,"availableToPromise":35,"reorderPoint":100,
                   "safetyStock":50,"atOrBelowReorderPoint":true,"belowSafetyStock":true},
                  {"sku":"EVAL-LID-90","usableOnHand":80,"availableToPromise":80,"atOrBelowReorderPoint":true,
                   "belowSafetyStock":false}]}""");

        ResultTable.Table table = ResultTable.of(execution, json).getFirst();

        assertThat(table.key()).isEqualTo("findLowStock.skus");
        assertThat(table.columns()).containsExactly("sku", "usableOnHand", "availableToPromise", "reorderPoint",
                "safetyStock", "atOrBelowReorderPoint", "belowSafetyStock");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().getFirst()).containsEntry("sku", "EVAL-CUP-12OZ").containsEntry("usableOnHand", 40L)
                .containsEntry("belowSafetyStock", true);
        assertThat(table.rows().get(1)).doesNotContainKey("reorderPoint").doesNotContainKey("safetyStock");
        assertThat(table.total()).isEqualTo(23);
        assertThat(table.truncated()).isTrue();
    }

    @Test
    @DisplayName("expiring lots: one table per list, each with its own total; an empty list makes none")
    void expiringLots() {
        ToolExecution execution = ok("findExpiringLots", """
                {"warningWindowDays":60,"expiringTotal":3,"expiringTruncated":true,
                 "expiring":[{"sku":"EVAL-LID-90","warehouseCode":"HCM","lotNumber":"EVAL-LID-A",
                   "expiryDate":"2026-11-04","daysToExpiry":6,"quantityAtLastCheck":30,
                   "lastCheckedAt":"2026-10-29T01:00:00+07:00","stillHeld":false,"expired":false}],
                 "expiredTotal":0,"expiredTruncated":false,"expired":[]}""");

        List<ResultTable.Table> tables = ResultTable.of(execution, json);

        assertThat(tables).singleElement().satisfies(table -> {
            assertThat(table.key()).isEqualTo("findExpiringLots.expiring");
            assertThat(table.rows().getFirst()).containsEntry("lotNumber", "EVAL-LID-A")
                    .containsEntry("expiryDate", "2026-11-04").containsEntry("lastCheckedAt",
                            "2026-10-29T01:00:00+07:00").doesNotContainKey("expired");
            assertThat(table.total()).isEqualTo(3);
            assertThat(table.truncated()).isTrue();
        });
    }

    @Test
    @DisplayName("stock per warehouse: the SKU the result is about goes into every row; a list never cut counts "
            + "its rows")
    void stockLevels() {
        ToolExecution execution = ok("getStockLevels", """
                {"sku":"EVAL-BOX-A4","companyAvailableToPromise":125,"warehouses":[
                  {"warehouseCode":"HCM","onHand":75,"inAvailableStatus":75,"heldOnStock":0,"availableToPromise":75},
                  {"warehouseCode":"EVN","onHand":50,"inAvailableStatus":50,"heldOnStock":0,"availableToPromise":50}]}""");

        ResultTable.Table table = ResultTable.of(execution, json).getFirst();

        assertThat(table.rows()).extracting(row -> row.get("sku")).containsExactly("EVAL-BOX-A4", "EVAL-BOX-A4");
        assertThat(table.rows()).extracting(row -> row.get("warehouseCode")).containsExactly("HCM", "EVN");
        assertThat(table.total()).isEqualTo(2);
        assertThat(table.truncated()).isFalse();
    }

    @Test
    @DisplayName("orders: a field of a nested record is a dotted column; an order not on hold has none of them")
    void orders() {
        ToolExecution execution = ok("findOrders", """
                {"total":3,"truncated":false,"orders":[
                  {"orderNumber":"SO-20991231-900001","status":"ON_HOLD","placedAt":"2026-10-28T09:15:00+07:00",
                   "total":12500000.50,"currency":"VND","hold":{"reason":"INVENTORY","note":"Thiếu 5 nắp"}},
                  {"orderNumber":"SO-20991231-900003","status":"PENDING_PAYMENT","total":4000000,"currency":"VND"},
                  {"orderNumber":"SO-20991231-900004","status":"CONFIRMED","total":12000000.00,"currency":"VND"}]}""");

        ResultTable.Table table = ResultTable.of(execution, json).getFirst();

        assertThat(table.rows().getFirst()).containsEntry("hold.reason", "INVENTORY")
                .containsEntry("hold.note", "Thiếu 5 nắp");
        assertThat(table.rows().get(1)).doesNotContainKeys("hold.reason", "hold.note");
        assertThat(table.rows().getFirst().get("total")).as("an amount keeps its exact decimals")
                .isEqualTo(new BigDecimal("12500000.50"));
        assertThat(table.rows().get(1).get("total")).isEqualTo(4000000L);
        assertThat(table.rows().get(2).get("total")).as("its trailing zeros too, never 1.2E+7")
                .hasToString("12000000.00");
    }

    @Test
    @DisplayName("one purchase order asked about: its lines are a table of their own, naming the order, counted by "
            + "its line count")
    void purchaseOrderLines() {
        ToolExecution execution = ok("findPurchaseOrders", """
                {"total":1,"truncated":false,"purchaseOrders":[
                  {"poNumber":"PO-HCM-DEMO-0002","status":"CONFIRMED","overdue":false,"lineCount":60,"lines":[
                    {"lineNo":1,"sku":"EVAL-LID-90","ordered":500,"received":200,"stillToReceive":300,
                     "status":"PARTIALLY_RECEIVED","unitPriceBeforeTax":1200}]}]}""");

        List<ResultTable.Table> tables = ResultTable.of(execution, json);

        assertThat(tables).extracting(ResultTable.Table::key)
                .containsExactly("findPurchaseOrders.purchaseOrders", "findPurchaseOrders.lines");
        ResultTable.Table lines = tables.get(1);
        assertThat(lines.rows().getFirst()).containsEntry("poNumber", "PO-HCM-DEMO-0002")
                .containsEntry("stillToReceive", 300L);
        assertThat(lines.total()).isEqualTo(60);
        assertThat(lines.truncated()).as("60 lines, one listed").isTrue();
        assertThat(tables.getFirst().rows().getFirst()).doesNotContainKeys("lines", "lineCount");
    }

    @Test
    @DisplayName("sales: the best sellers are the table, the period's totals are not")
    void bestSellers() {
        ToolExecution execution = ok("getSalesSummary", """
                {"from":"2026-10-22","to":"2026-10-28","totals":{"orders":12,"revenue":35000000},
                 "topSkus":[{"sku":"EVAL-INJ-01","productName":"Cốc in","quantitySold":300,"revenue":9000000}]}""");

        assertThat(ResultTable.of(execution, json)).singleElement().satisfies(table -> {
            assertThat(table.key()).isEqualTo("getSalesSummary.topSkus");
            assertThat(table.rows().getFirst()).containsOnlyKeys("sku", "productName", "quantitySold", "revenue");
        });
        assertThat(ResultTable.of(ok("getSalesSummary", "{\"topSkus\":[]}"), json)).isEmpty();
    }

    @Test
    @DisplayName("no table from a failed call, a tool whose rows are not tabled, or a result that is not JSON")
    void noTable() {
        String rows = "{\"total\":1,\"truncated\":false,\"skus\":[{\"sku\":\"A\"}]}";
        assertThat(ResultTable.of(new ToolExecution(new ToolCall("toolu_1", "findLowStock", "{}"),
                ToolOutcome.DENIED, "NOT_PERMITTED: Missing permission: inventory-alerts:READ", 1), json)).isEmpty();
        assertThat(ResultTable.of(ok("findOrder", rows), json)).isEmpty();
        assertThat(ResultTable.of(ok("findLowStock", "RESULT_TOO_LARGE: ask again"), json)).isEmpty();
        assertThat(ResultTable.of(ok("findLowStock", "{\"note\":\"company-wide\",\"skus\":[]}"), json)).isEmpty();
        assertThat(ResultTable.of(List.of(ok("findLowStock", rows), ok("getStockLots", "{\"locations\":[]}")),
                json)).hasSize(1);
    }

    @Test
    @DisplayName("the system prompt tells the model which lookups the panel shows as tables, and every one of them")
    void thePromptNamesEveryTabledTool() {
        String prompt = new SystemPrompt().text();
        String bullet = prompt.lines().filter(line -> line.contains("as a table under your answer")).findFirst()
                .orElseThrow(() -> new AssertionError("the prompt says the panel shows rows as tables"));

        assertThat(ResultTable.tools()).allSatisfy(tool -> assertThat(bullet).contains(tool));
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ToolExecution ok(String tool, String result) {
        return new ToolExecution(new ToolCall("toolu_" + tool, tool, "{}"), ToolOutcome.OK, result, 1);
    }

    /** {@code answer} as the tool named {@code name} hands it back, written as production writes it. */
    private ToolExecution stored(String name, Object answer) {
        AssistantTool tool = new AssistantTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "test tool", ToolSchema.object().string("sku", "a SKU").build());
            }

            @Override
            public String mirrors() {
                return null;
            }

            @Override
            public Object run(ToolArguments arguments) {
                return answer;
            }
        };
        ToolExecution execution = new AssistantTools(List.of(tool), json).execute(new ToolCall("toolu_1", name, "{}"));
        assertThat(execution.outcome()).as(execution.result()).isEqualTo(ToolOutcome.OK);
        return execution;
    }

    /** An instance of {@code type} with every field set, lists holding one element: every column has a value. */
    private static Object sample(Type type) throws Exception {
        Class<?> raw = type instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType()
                : (Class<?>) type;
        if (raw == String.class) {
            return "S-1";
        }
        if (raw == int.class || raw == Integer.class) {
            return 7;
        }
        if (raw == long.class || raw == Long.class) {
            return 7L;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return true;
        }
        if (raw == double.class || raw == Double.class) {
            return 12.5;
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal("12.50");
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (raw == LocalDate.class) {
            return LocalDate.of(2026, 11, 4);
        }
        if (raw == Instant.class) {
            return Instant.parse("2026-10-28T03:00:00Z");
        }
        if (raw == java.util.UUID.class) {
            return java.util.UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
        }
        if (raw == List.class || raw == Set.class) {
            Object element = sample(((ParameterizedType) type).getActualTypeArguments()[0]);
            return raw == List.class ? List.of(element) : Set.of(element);
        }
        if (raw == Map.class) {
            return new LinkedHashMap<>();
        }
        if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = sample(components[i].getGenericType());
            }
            Constructor<?> canonical = raw.getDeclaredConstructor(Arrays.stream(components)
                    .map(RecordComponent::getType).toArray(Class<?>[]::new));
            canonical.setAccessible(true);
            return canonical.newInstance(values);
        }
        throw new IllegalArgumentException("no sample value for " + type);
    }
}
