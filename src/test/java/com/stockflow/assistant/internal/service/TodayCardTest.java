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
    /** 09:15 on 28/10 in Vietnam. */
    private static final Instant PLACED = Instant.parse("2026-10-28T02:15:00Z");

    private static final List<String> TOOLS = List.of("findOrders", "findGoodsReceipts");

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
        answers.put("findOrders", arguments -> orders(arguments.choice("status", FindOrdersTool.STATUSES)));
        answers.put("findGoodsReceipts", arguments -> receipts(23));
        tools = spy(new AssistantTools(TOOLS.stream().map(this::tool).toList(), applicationMapper()));
    }

    @Test
    @DisplayName("a user gets a section for each tool they may use and nothing else, in the card's fixed order")
    void sectionsFollowTheGrantedTools() {
        assertThat(keys(card("findOrders", "getSalesSummary")))
                .as("sales").containsExactly("heldOrders", "awaitingPayment", "awaitingStock");
        assertThat(keys(card("findGoodsReceipts", "getStockLots")))
                .as("warehouse staff").containsExactly("receiptsToPutAway");
        assertThat(keys(card(TOOLS.toArray(String[]::new)))).containsExactly("heldOrders", "awaitingPayment",
                "awaitingStock", "receiptsToPutAway");
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
        verify(tools, times(4)).execute(calls.capture(), eq(ToolChannel.TODAY));
        Map<String, ToolSpec> specs = new HashMap<>();
        Stream.of(new FindOrdersTool(null, null, null), new FindGoodsReceiptsTool(null))
                .forEach(tool -> specs.put(tool.spec().name(), tool.spec()));
        Map<String, Integer> maxRows = Map.of("findOrders", FindOrdersTool.MAX_ROWS,
                "findGoodsReceipts", FindGoodsReceiptsTool.MAX_ROWS);
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
        assertThat(calls.getAllValues().stream().filter(call -> call.name().equals("findOrders"))
                .map(call -> call.arguments())).as("the three order queues, by status")
                .anyMatch(arguments -> arguments.contains("\"ON_HOLD\""))
                .anyMatch(arguments -> arguments.contains("\"PENDING_PAYMENT\""))
                .anyMatch(arguments -> arguments.contains("\"AWAITING_STOCK\""));
    }

    @Test
    @DisplayName("every column a section shows is a field of its tool's own row record, a nested one by its path")
    void columnsAreFieldsOfTheToolsRows() {
        Map<String, Class<?>> rowOf = Map.of("findOrders", FindOrdersTool.Row.class,
                "findGoodsReceipts", FindGoodsReceiptsTool.Row.class);

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
        TodayCard.Card card = card("findGoodsReceipts", "findOrders");

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
                .contains("on hold for payment", "due before delivery");
        assertThat(table(card, "awaitingPayment").columns()).doesNotContain("hold.reason", "status");
        assertThat(section(card, "awaitingStock").note()).isNull();

        ResultTable.Table receipts = table(card, "receiptsToPutAway");
        Map<String, Object> receipt = receipts.rows().getFirst();
        assertThat(receipt.keySet()).containsExactlyElementsOf(receipts.columns());
        assertThat(cell(receipt, "producerName").isNull()).as("left out of the tool's JSON, present in the row")
                .isTrue();
        assertThat(receipts.total()).isEqualTo(23);
        assertThat(receipts.columns()).doesNotContain("status", "closedAt");

        String written = applicationMapper().writeValueAsString(List.of(order, receipt));
        assertThat(written).as("as the endpoint writes it, null fields left out of objects")
                .contains("\"total\":12960000.00", "\"producerName\":null",
                        "\"placedAt\":\"2026-10-28T09:15:00+07:00\"")
                .doesNotContain("E+7", "E7");
    }

    @Test
    @DisplayName("a tool that fails fails only its own sections: no table, a note, and the other sections answer")
    void aFailingToolFailsOnlyItsSections() {
        answers.put("findOrders", arguments -> {
            throw new IllegalStateException("order store unreachable");
        });

        TodayCard.Card card = card("findGoodsReceipts", "findOrders");

        assertThat(keys(card)).containsExactly("heldOrders", "awaitingPayment", "awaitingStock", "receiptsToPutAway");
        for (String key : List.of("heldOrders", "awaitingPayment", "awaitingStock")) {
            TodayCard.Section failed = section(card, key);
            assertThat(failed.tool()).isEqualTo("findOrders");
            assertThat(failed.outcome()).isEqualTo(ToolOutcome.FAILED);
            assertThat(failed.table()).isNull();
            assertThat(failed.note()).contains("could not be read").doesNotContain("order store");
        }
        assertThat(section(card, "receiptsToPutAway").outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(table(card, "receiptsToPutAway").rows()).hasSize(2);
    }

    @Test
    @DisplayName("a tool that refuses the user is DENIED, with a note and no rows; the other sections answer")
    void aRefusingToolIsDenied() {
        answers.put("findOrders", arguments -> {
            throw new PermissionDeniedException(PermissionCode.of("sales-orders", Action.READ));
        });

        TodayCard.Card card = card("findOrders", "findGoodsReceipts");

        for (String key : List.of("heldOrders", "awaitingPayment", "awaitingStock")) {
            assertThat(section(card, key).outcome()).isEqualTo(ToolOutcome.DENIED);
            assertThat(section(card, key).table()).isNull();
            assertThat(section(card, key).note()).contains("permissions");
        }
        assertThat(section(card, "receiptsToPutAway").outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(section(card, "receiptsToPutAway").note()).isNull();
    }

    @Test
    @DisplayName("a list without its count is never shown: an answer the card cannot read is FAILED")
    void aListWithoutItsCountIsNotShown() {
        answers.put("findGoodsReceipts", arguments -> java.util.Map.of("receipts", List.of()));

        TodayCard.Section section = section(card("findGoodsReceipts"), "receiptsToPutAway");

        assertThat(section.outcome()).isEqualTo(ToolOutcome.FAILED);
        assertThat(section.table()).isNull();
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

    private static FindGoodsReceiptsTool.Answer receipts(int total) {
        return new FindGoodsReceiptsTool.Answer(List.of(
                new FindGoodsReceiptsTool.Row("GR-20261028-0001", "PRODUCTION", "LSX-20261020-0001", null, "HCM",
                        "IN_PUTAWAY", PLACED, PLACED.plusSeconds(600), null),
                new FindGoodsReceiptsTool.Row("GR-20261028-0002", "PRODUCTION", "LSX-20261020-0002",
                        "Xưởng in Bao Bì Việt", "HCM", "IN_PUTAWAY", PLACED, PLACED.plusSeconds(900), null)),
                total, total > 2);
    }

    private static FindOrdersTool.Answer orders(String status) {
        if (status.equals("ON_HOLD")) {
            return new FindOrdersTool.Answer(List.of(new FindOrdersTool.Row("SO-20261028-000101", "ON_HOLD", PLACED,
                    new BigDecimal("12960000.00"), "VND",
                    new FindOrdersTool.Hold("PAYMENT", "Chờ đối soát, gọi [phone]", "CONFIRMED"), null, null)),
                    3, true, null);
        }
        return new FindOrdersTool.Answer(List.of(new FindOrdersTool.Row("SO-20261028-000102", status, PLACED,
                new BigDecimal("500000"), "VND", null, null, null)), 1,
                false, null);
    }
}
