package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The "today" card over the real {@link AssistantTools}, with stand-in tools answering the real tools' own records:
 * which sections a user gets, the arguments each tool is called with, how a table is read from an answer, and that
 * one tool failing or refusing touches only its own sections. End to end, with the seeded roles and the real tools, it
 * is {@code AssistantTodayIntegrationTest}.
 */
class TodayCardTest {

    /** 07:30 on 29/10/2026 in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-10-29T00:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    /** 09:15 on 28/10 in Vietnam. */
    private static final Instant PLACED = Instant.parse("2026-10-28T02:15:00Z");
    /** 01:00 on 29/10 in Vietnam: the nightly expiry check. */
    private static final Instant NIGHTLY = Instant.parse("2026-10-28T18:00:00Z");

    private static final List<String> TOOLS = List.of("findExpiringLots", "findLowStock", "findOrders",
            "findProductionOrders", "findPurchaseOrders");

    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), "staff", Set.of(Role.SYSTEM_ADMIN),
            Set.of(), DataScope.ALL, Set.of());

    private final ToolAvailability availability = mock(ToolAvailability.class);
    /** What each stand-in tool answers, by tool name. */
    private final Map<String, Function<ToolArguments, Object>> answers = new HashMap<>();
    private AssistantTools tools;

    /** What Spring Boot builds from {@code application.yml}: java.time as ISO text, null fields left out. */
    private static ObjectMapper applicationMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    @BeforeEach
    void setUp() {
        answers.put("findLowStock", arguments -> lowStock(23));
        answers.put("findExpiringLots", arguments -> lots(30));
        answers.put("findOrders", arguments -> orders(arguments.choice("status", FindOrdersTool.STATUSES)));
        answers.put("findPurchaseOrders", arguments -> purchaseOrders(
                arguments.choice("status", FindPurchaseOrdersTool.STATUSES), false));
        answers.put("findProductionOrders", arguments -> production());
        tools = spy(new AssistantTools(TOOLS.stream().map(this::tool).toList(), applicationMapper()));
    }

    @Test
    @DisplayName("a user gets a section for each tool they may use and nothing else, in the card's fixed order")
    void sectionsFollowTheGrantedTools() {
        assertThat(keys(card("findExpiringLots", "findLowStock", "getStockLevels", "searchKnowledge")))
                .as("a planner").containsExactly("lowStock", "expiredLots", "expiringLots");
        assertThat(keys(card("findOrders", "findProductionOrders", "getSalesSummary")))
                .as("a coordinator").containsExactly("heldOrders", "awaitingPayment", "overdueProduction");
        assertThat(keys(card("findExpiringLots", "findPurchaseOrders", "findProductionOrders", "getStockLots")))
                .as("a warehouse manager, refused company-wide low stock by ToolAvailability")
                .containsExactly("expiredLots", "expiringLots", "overduePurchaseOrders", "overdueProduction");
        assertThat(keys(card(TOOLS.toArray(String[]::new)))).containsExactly("lowStock", "expiredLots",
                "expiringLots", "heldOrders", "awaitingPayment", "overduePurchaseOrders", "overdueProduction");
    }

    @Test
    @DisplayName("a user who may use none of the card's tools gets an empty card, and no tool runs")
    void nothingGrantedNothingRuns() {
        TodayCard.Card card = card("searchCatalog", "searchKnowledge");

        assertThat(card.sections()).isEmpty();
        assertThat(card.generatedAt()).isEqualTo(NOW);
        verify(tools, never()).execute(any(), any());
    }

    @Test
    @DisplayName("every call gives exactly the tool's schema properties, with allowed values and limits in range")
    @SuppressWarnings("unchecked")
    void everyCallMatchesItsToolsSchema() throws Exception {
        card(TOOLS.toArray(String[]::new));

        ArgumentCaptor<ToolCall> calls = ArgumentCaptor.forClass(ToolCall.class);
        verify(tools, times(7)).execute(calls.capture(), eq(ToolChannel.TODAY));
        Map<String, ToolSpec> specs = new HashMap<>();
        Stream.of(new FindLowStockTool(null, null), new FindExpiringLotsTool(null, null),
                        new FindOrdersTool(null, null), new FindPurchaseOrdersTool(null, null, null),
                        new FindProductionOrdersTool(null, null, null))
                .forEach(tool -> specs.put(tool.spec().name(), tool.spec()));
        Map<String, Integer> maxRows = Map.of("findLowStock", FindLowStockTool.MAX_ROWS,
                "findExpiringLots", FindExpiringLotsTool.MAX_ROWS, "findOrders", FindOrdersTool.MAX_ROWS,
                "findPurchaseOrders", FindPurchaseOrdersTool.MAX_ROWS,
                "findProductionOrders", FindProductionOrdersTool.MAX_ROWS);
        ObjectMapper json = new ObjectMapper();
        for (ToolCall call : calls.getAllValues()) {
            ToolSpec spec = specs.get(call.name());
            JsonNode arguments = json.readTree(call.arguments());
            List<String> given = new ArrayList<>();
            arguments.fieldNames().forEachRemaining(given::add);
            assertThat(given).as("%s %s", call.name(), call.arguments())
                    .containsExactlyInAnyOrderElementsOf((List<String>) spec.inputSchema().get("required"));
            ((Map<String, Map<String, Object>>) spec.inputSchema().get("properties")).forEach((name, property) -> {
                JsonNode value = arguments.get(name);
                if ("integer".equals(property.get("type"))) {
                    assertThat(value.isInt()).as("%s.%s is an integer", call.name(), name).isTrue();
                } else {
                    assertThat(value.isTextual()).as("%s.%s is a string", call.name(), name).isTrue();
                }
                if (property.containsKey("enum")) {
                    assertThat((List<String>) property.get("enum")).as("%s.%s", call.name(), name)
                            .contains(value.asText());
                }
            });
            assertThat(arguments.get("limit").asInt()).as("%s limit", call.name())
                    .isBetween(1, maxRows.get(call.name()));
        }
        assertThat(calls.getAllValues().stream().filter(call -> call.name().equals("findExpiringLots")))
                .singleElement().satisfies(call -> assertThat(json.readTree(call.arguments()).get("withinDays").asInt())
                        .isEqualTo(TodayCard.EXPIRY_DAYS).isLessThanOrEqualTo(FindExpiringLotsTool.MAX_DAYS));
    }

    @Test
    @DisplayName("every column a section shows is a field of its tool's own row record, a nested one by its path")
    void columnsAreFieldsOfTheToolsRows() {
        Map<String, Class<?>> rowOf = Map.of("findLowStock", FindLowStockTool.Row.class,
                "findExpiringLots", FindExpiringLotsTool.Row.class, "findOrders", FindOrdersTool.Row.class,
                "findPurchaseOrders", FindPurchaseOrdersTool.Row.class,
                "findProductionOrders", FindProductionOrdersTool.Row.class);

        for (TodayCard.Section section : card(TOOLS.toArray(String[]::new)).sections()) {
            assertThat(section.outcome()).as(section.key()).isEqualTo(ToolOutcome.OK);
            assertThat(section.table().key()).isEqualTo(section.key());
            assertThat(section.table().tool()).isEqualTo(section.tool());
            for (String column : section.table().columns()) {
                assertThat(resolves(rowOf.get(section.tool()), column))
                        .as("%s shows %s, a field of %s's row", section.key(), column, section.tool()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a row carries every column as the tool wrote it: Vietnam time, an exact amount, a nested hold, and "
            + "an explicit null for a field the tool left out")
    void rowsAsTheToolWroteThem() throws Exception {
        TodayCard.Card card = card("findLowStock", "findOrders");

        ResultTable.Table held = table(card, "heldOrders");
        Map<String, Object> order = held.rows().getFirst();
        assertThat(order.keySet()).containsExactlyElementsOf(held.columns());
        assertThat(cell(order, "orderNumber").asText()).isEqualTo("SO-20261028-000101");
        assertThat(cell(order, "placedAt").asText()).isEqualTo("2026-10-28T09:15:00+07:00");
        assertThat(cell(order, "hold.reason").asText()).isEqualTo("PAYMENT");
        assertThat(cell(order, "hold.note").asText()).isEqualTo("Chờ đối soát, gọi [phone]");
        assertThat(cell(order, "hold.statusBeforeHold").asText()).isEqualTo("CONFIRMED");
        assertThat(cell(order, "total").decimalValue()).as("scale kept").isEqualTo(new BigDecimal("12960000.00"));
        assertThat(held.columns()).as("the same on every held order").doesNotContain("status");
        assertThat(held.total()).isEqualTo(3);
        assertThat(held.truncated()).isTrue();
        assertThat(section(card, "heldOrders").note()).isNull();
        assertThat(section(card, "awaitingPayment").note()).as("what waiting for payment leaves out")
                .contains("on hold for payment", "balance is due before delivery");
        assertThat(table(card, "awaitingPayment").columns()).doesNotContain("hold.reason", "status");

        ResultTable.Table low = table(card, "lowStock");
        Map<String, Object> sku = low.rows().getFirst();
        assertThat(sku.keySet()).containsExactlyElementsOf(low.columns());
        assertThat(cell(sku, "safetyStock").isNull()).as("left out of the tool's JSON, present in the row").isTrue();
        assertThat(low.total()).isEqualTo(23);

        String written = applicationMapper().writeValueAsString(List.of(order, sku));
        assertThat(written).as("as the endpoint writes it, null fields left out of objects")
                .contains("\"total\":12960000.00", "\"safetyStock\":null", "\"placedAt\":\"2026-10-28T09:15:00+07:00\"")
                .doesNotContain("E+7", "E7");
    }

    @Test
    @DisplayName("a tool that fails fails only its own sections: no table, a note, and the other sections answer")
    void aFailingToolFailsOnlyItsSections() {
        answers.put("findExpiringLots", arguments -> {
            throw new IllegalStateException("alert store unreachable");
        });

        TodayCard.Card card = card("findLowStock", "findExpiringLots");

        assertThat(keys(card)).containsExactly("lowStock", "expiredLots", "expiringLots");
        for (String key : List.of("expiredLots", "expiringLots")) {
            TodayCard.Section failed = section(card, key);
            assertThat(failed.tool()).isEqualTo("findExpiringLots");
            assertThat(failed.outcome()).isEqualTo(ToolOutcome.FAILED);
            assertThat(failed.table()).isNull();
            assertThat(failed.note()).contains("could not be read").doesNotContain("alert store");
        }
        assertThat(section(card, "lowStock").outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(table(card, "lowStock").rows()).hasSize(2);
    }

    @Test
    @DisplayName("a tool that refuses the user is DENIED, with a note and no rows; the other sections answer")
    void aRefusingToolIsDenied() {
        answers.put("findOrders", arguments -> {
            throw new PermissionDeniedException(PermissionCode.of("sales-orders", Action.READ));
        });

        TodayCard.Card card = card("findOrders", "findProductionOrders");

        for (String key : List.of("heldOrders", "awaitingPayment")) {
            assertThat(section(card, key).outcome()).isEqualTo(ToolOutcome.DENIED);
            assertThat(section(card, key).table()).isNull();
            assertThat(section(card, key).note()).contains("permissions");
        }
        assertThat(section(card, "overdueProduction").outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(section(card, "overdueProduction").note()).isNull();
    }

    @Test
    @DisplayName("overdue purchase orders: the rows the tool marks overdue in both statuses sent to a supplier, most "
            + "days late first, counted; a refused or not yet late order is not one")
    void overduePurchaseOrders() {
        TodayCard.Section section = section(card("findPurchaseOrders"), "overduePurchaseOrders");

        assertThat(section.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(section.tool()).isEqualTo("findPurchaseOrders");
        assertThat(section.note()).isNull();
        ResultTable.Table table = section.table();
        assertThat(table.rows()).extracting(row -> cell(row, "poNumber").asText())
                .containsExactly("PO-HCM-0003", "PO-HCM-0001", "PO-HCM-0004");
        assertThat(table.rows()).extracting(row -> cell(row, "daysLate").asLong()).containsExactly(9L, 2L, 2L);
        assertThat(table.total()).isEqualTo(3);
        assertThat(table.truncated()).isFalse();
        assertThat(table.columns()).doesNotContain("overdue", "lines", "rejectionReason");
        verify(tools, times(2)).execute(any(), eq(ToolChannel.TODAY));
    }

    @Test
    @DisplayName("when a status has more orders than were read, an older late one may be missing: truncated, and "
            + "the note says how many were read")
    void overduePurchaseOrdersReadInPart() {
        answers.put("findPurchaseOrders", arguments -> purchaseOrders(
                arguments.choice("status", FindPurchaseOrdersTool.STATUSES), true));

        TodayCard.Section section = section(card("findPurchaseOrders"), "overduePurchaseOrders");

        assertThat(section.table().total()).as("the late ones among those read").isEqualTo(3);
        assertThat(section.table().truncated()).isTrue();
        assertThat(section.note()).contains(String.valueOf(TodayCard.PURCHASE_ORDERS_READ));
    }

    @Test
    @DisplayName("one of the two purchase-order reads failing fails the section: half the list would read as all")
    void aFailedPurchaseOrderReadShowsNoneOfThem() {
        answers.put("findPurchaseOrders", arguments -> {
            if (arguments.choice("status", FindPurchaseOrdersTool.STATUSES).equals("PARTIALLY_RECEIVED")) {
                throw new IllegalStateException("procurement unreachable");
            }
            return purchaseOrders("CONFIRMED", false);
        });

        TodayCard.Section section = section(card("findPurchaseOrders"), "overduePurchaseOrders");

        assertThat(section.outcome()).isEqualTo(ToolOutcome.FAILED);
        assertThat(section.table()).isNull();
        assertThat(section.note()).isNotBlank();
    }

    @Test
    @DisplayName("a list without its count is never shown: an answer the card cannot read is FAILED")
    void aListWithoutItsCountIsNotShown() {
        // findLowStock's answer to a user limited to warehouses: no rows, no total, a note.
        answers.put("findLowStock", arguments -> lowStock(null));

        TodayCard.Section section = section(card("findLowStock"), "lowStock");

        assertThat(section.outcome()).isEqualTo(ToolOutcome.FAILED);
        assertThat(section.table()).isNull();
    }

    @Test
    @DisplayName("the lots say their quantities are the nightly check's, and the expiring ones name a warning window "
            + "shorter than the days asked for")
    void expiryNotes() {
        TodayCard.Card usual = card("findExpiringLots");
        assertThat(section(usual, "expiredLots").note()).contains("nightly");
        assertThat(section(usual, "expiringLots").note()).contains("nightly").doesNotContain("days of their expiry");
        assertThat(cell(table(usual, "expiredLots").rows().getFirst(), "stillHeld").asBoolean()).isTrue();
        assertThat(table(usual, "expiredLots").total()).isEqualTo(2);
        assertThat(table(usual, "expiredLots").truncated()).isTrue();
        assertThat(table(usual, "expiringLots").columns()).doesNotContain("expired", "stillHeld");

        answers.put("findExpiringLots", arguments -> lots(7));
        TodayCard.Card shortWindow = card("findExpiringLots");
        assertThat(section(shortWindow, "expiringLots").note()).contains("within 7 days");
        assertThat(section(shortWindow, "expiredLots").note()).doesNotContain("within 7 days");
    }

    // ------------------------------------------------------------------------------------------------------------

    private TodayCard.Card card(String... granted) {
        when(availability.grantedTo(USER)).thenReturn(List.of(granted));
        return new TodayCard(new ParallelTools(tools), availability, applicationMapper(), new Messages(new StaticMessageSource()),
                Clock.fixed(NOW, ZoneOffset.UTC), org.mockito.Mockito.mock(ToolRunRecorder.class)).of(USER);
    }

    private AssistantTool tool(String name) {
        ToolSpec spec = new ToolSpec(name, "stands in for " + name, Map.of());
        return new AssistantTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public String mirrors() {
                return null;
            }

            @Override
            public Object run(ToolArguments arguments) {
                return answers.get(name).apply(arguments);
            }
        };
    }

    private static List<String> keys(TodayCard.Card card) {
        return card.sections().stream().map(TodayCard.Section::key).toList();
    }

    private static TodayCard.Section section(TodayCard.Card card, String key) {
        return card.sections().stream().filter(section -> section.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no section " + key + " in " + keys(card)));
    }

    /** A cell as the card built it: a JSON node, a null one where the tool left the field out. */
    private static JsonNode cell(Map<String, Object> row, String column) {
        return (JsonNode) row.get(column);
    }

    private static ResultTable.Table table(TodayCard.Card card, String key) {
        ResultTable.Table table = section(card, key).table();
        assertThat(table).as("%s has a table", key).isNotNull();
        return table;
    }

    /** Whether {@code path} (a field, or {@code field.field}) names a component of the record {@code type}. */
    private static boolean resolves(Class<?> type, String path) {
        Class<?> current = type;
        for (String field : path.split("\\.")) {
            if (current == null || !current.isRecord()) {
                return false;
            }
            Class<?> next = null;
            for (RecordComponent component : current.getRecordComponents()) {
                if (component.getName().equals(field)) {
                    next = component.getType();
                }
            }
            if (next == null) {
                return false;
            }
            current = next;
        }
        return true;
    }

    // The tools' own records, as they answer -------------------------------------------------------------------

    /** @param total null: the list withheld from a user limited to warehouses */
    private static FindLowStockTool.Answer lowStock(Integer total) {
        if (total == null) {
            return new FindLowStockTool.Answer(null, false, List.of(), "Low-stock thresholds are company-wide.");
        }
        return new FindLowStockTool.Answer(total, true, List.of(
                new FindLowStockTool.Row("CUP-12OZ-KRAFT", 40, 25, 100, null, true, false),
                new FindLowStockTool.Row("LID-90", 3, 0, 60, 50, true, true)), null);
    }

    private static FindExpiringLotsTool.Answer lots(int warningWindowDays) {
        return new FindExpiringLotsTool.Answer(warningWindowDays, warningWindowDays < 14 ? "window" : null,
                1, false, List.of(new FindExpiringLotsTool.Row("LID-90", "HCM", "L-2401", TODAY.plusDays(5), 5L, 20,
                        NIGHTLY, false, false)),
                2, true, List.of(new FindExpiringLotsTool.Row("CUP-1", "HCM", "L-2301", TODAY.minusDays(3), -3L, 8,
                        NIGHTLY, true, true)));
    }

    private static FindOrdersTool.Answer orders(String status) {
        if (status.equals("ON_HOLD")) {
            return new FindOrdersTool.Answer(List.of(new FindOrdersTool.Row("SO-20261028-000101", "ON_HOLD", PLACED,
                    new BigDecimal("12960000.00"), "VND", "PREPAID", "PAID", new BigDecimal("12960000.00"), null,
                    new FindOrdersTool.Hold("PAYMENT", "Chờ đối soát, gọi [phone]", "CONFIRMED"), null, null)),
                    3, true, null);
        }
        return new FindOrdersTool.Answer(List.of(new FindOrdersTool.Row("SO-20261028-000102", status, PLACED,
                new BigDecimal("500000"), "VND", "PREPAID", "UNPAID", BigDecimal.ZERO, null, null, null, null)), 1,
                false, null);
    }

    /** @param more the status has more orders than were read */
    private static FindPurchaseOrdersTool.Answer purchaseOrders(String status, boolean more) {
        List<FindPurchaseOrdersTool.Row> rows = status.equals("CONFIRMED")
                ? List.of(po("PO-HCM-0001", status, "CONFIRMED", 2L), po("PO-HCM-0002", status, "CONFIRMED", null),
                        po("PO-HCM-0005", status, "REJECTED", null))
                : List.of(po("PO-HCM-0003", status, "CONFIRMED", 9L), po("PO-HCM-0004", status, "CONFIRMED", 2L));
        return new FindPurchaseOrdersTool.Answer(rows, more ? 41 : rows.size(), more, List.of(), null);
    }

    /**
     * @param daysLate null for an order the tool does not judge overdue: due in three days, or refused by the supplier
     *                 (past its date, never coming)
     */
    private static FindPurchaseOrdersTool.Row po(String number, String status, String supplierAnswer, Long daysLate) {
        boolean overdue = daysLate != null;
        boolean refused = supplierAnswer.equals("REJECTED");
        LocalDate expected = overdue ? TODAY.minusDays(daysLate) : refused ? TODAY.minusDays(4) : TODAY.plusDays(3);
        return new FindPurchaseOrdersTool.Row(number, "STANDARD", "GOVIET", "Xưởng gỗ Việt", "HCM", status,
                supplierAnswer, TODAY.minusDays(30), expected, overdue, daysLate, new BigDecimal("12000000.00"),
                new BigDecimal("960000.00"), new BigDecimal("12960000.00"), "VND",
                refused ? "Hết nguyên liệu" : null, null, null);
    }

    private static FindProductionOrdersTool.Answer production() {
        return new FindProductionOrdersTool.Answer(null, List.of(new FindProductionOrdersTool.Row("LSX-20261020-0001",
                "ORDER", "IN_HOUSE", "PRINTING", null, "HCM", "CUP-12OZ-KRAFT", 10_000, 5_800, TODAY.minusDays(1),
                true, "SO-20261015-000077", null, null, PLACED)), 1, false, null, null);
    }
}
