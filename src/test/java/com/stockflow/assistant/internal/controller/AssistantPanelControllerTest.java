package com.stockflow.assistant.internal.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.controller.dto.TodayResponse;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.service.ResultTable;
import com.stockflow.assistant.internal.service.TodayCard;
import com.stockflow.assistant.internal.service.ToolAvailability;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The "today" endpoint's own rules: guarded as the panel is, a rate-limit budget apart from the questions', a portal
 * customer refused before any tool runs, and the card written as built. The card itself is {@code TodayCardTest}; the
 * whole stack with the seeded roles is {@code AssistantTodayIntegrationTest}.
 */
class AssistantPanelControllerTest {

    private static final CurrentUser PLANNER = new CurrentUser(UUID.randomUUID(), "planner",
            Set.of(Role.INVENTORY_PLANNER), Set.of(), DataScope.ALL, Set.of());
    private static final CurrentUser CUSTOMER = new CurrentUser(UUID.randomUUID(), "customer", Set.of(Role.CUSTOMER),
            Set.of(), DataScope.ALL, Set.of());

    @Test
    @DisplayName("the card has a budget of its own, one card a minute per user, never the questions' bucket")
    void theCardHasABudgetOfItsOwn() {
        RateLimit card = handler(AssistantPanelController.class, "today").getAnnotation(RateLimit.class);
        RateLimit questions = handler(AssistantController.class, "ask").getAnnotation(RateLimit.class);

        assertThat(card).as("the card is rate-limited").isNotNull();
        assertThat(List.of(card.limit(), card.perSeconds(), card.key())).isEqualTo(List.of(1, 60, RateLimit.Key.USER));
        // One bucket under two capacities: RedisRateLimiter bounds the tokens by each call's capacity, so opening the
        // card would cut the questions' burst of ten down to one.
        assertThat(card.bucket()).isNotBlank().isNotEqualTo(questions.bucket());
    }

    @Test
    @DisplayName("the card is guarded as the capabilities are: assistant-conversations READ, scope OWN, a public "
            + "handler")
    void guardedAsThePanel() {
        Method today = handler(AssistantPanelController.class, "today");
        RequiresPermission guard = today.getAnnotation(RequiresPermission.class);
        RequiresPermission panel = handler(AssistantPanelController.class, "capabilities")
                .getAnnotation(RequiresPermission.class);

        assertThat(Modifier.isPublic(today.getModifiers())).as("a proxy advises public handlers only").isTrue();
        assertThat(List.of(guard.resource(), guard.action(), guard.scope()))
                .isEqualTo(List.of(AssistantResources.CONVERSATIONS, Action.READ, DataScope.OWN))
                .isEqualTo(List.of(panel.resource(), panel.action(), panel.scope()));
    }

    @Test
    @DisplayName("a portal customer is refused before any tool runs")
    void customersAreRefused() {
        TodayCard card = mock(TodayCard.class);
        AssistantPanelController controller = new AssistantPanelController(mock(ToolAvailability.class), card);

        assertThatThrownBy(() -> controller.today(CUSTOMER))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(card);
    }

    @Test
    @DisplayName("the card is written as built: a section without a table says so with a null, a row keeps its nulls "
            + "and its exact amounts")
    void writesTheCardAsBuilt() throws Exception {
        Instant generatedAt = Instant.parse("2026-10-29T00:30:00Z");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sku", TextNode.valueOf("LID-90"));
        row.put("safetyStock", NullNode.getInstance());
        row.put("price", DecimalNode.valueOf(new BigDecimal("12960000.00")));
        TodayCard card = mock(TodayCard.class);
        when(card.of(PLANNER)).thenReturn(new TodayCard.Card(generatedAt, List.of(
                new TodayCard.Section("lowStock", "findLowStock", ToolOutcome.OK, new ResultTable.Table("lowStock",
                        "findLowStock", List.of("sku", "safetyStock", "price"), List.of(row), 23, true), null),
                new TodayCard.Section("expiredLots", "findExpiringLots", ToolOutcome.FAILED, null,
                        "Chưa đọc được."))));

        TodayResponse response = new AssistantPanelController(mock(ToolAvailability.class), card).today(PLANNER)
                .data();

        JsonNode written = applicationMapper().readTree(applicationMapper().writeValueAsString(response));
        assertThat(written.path("generatedAt").asText()).isEqualTo("2026-10-29T00:30:00Z");
        JsonNode lowStock = written.path("sections").path(0);
        assertThat(lowStock.path("outcome").asText()).isEqualTo("OK");
        assertThat(lowStock.has("note")).as("a note is always sent, null included").isTrue();
        assertThat(lowStock.path("note").isNull()).isTrue();
        assertThat(lowStock.path("table").path("total").asLong()).isEqualTo(23);
        assertThat(lowStock.path("table").path("truncated").asBoolean()).isTrue();
        assertThat(texts(lowStock.path("table").path("columns"))).containsExactly("sku", "safetyStock", "price");
        JsonNode cells = lowStock.path("table").path("rows").path(0);
        assertThat(cells.has("safetyStock")).as("every column, null included").isTrue();
        assertThat(cells.path("safetyStock").isNull()).isTrue();
        assertThat(applicationMapper().writeValueAsString(response)).contains("\"price\":12960000.00");
        JsonNode failed = written.path("sections").path(1);
        assertThat(failed.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(failed.has("table")).as("a table is always sent, null included").isTrue();
        assertThat(failed.path("table").isNull()).isTrue();
        assertThat(failed.path("note").asText()).isEqualTo("Chưa đọc được.");
    }

    // ------------------------------------------------------------------------------------------------------------

    /** What Spring Boot builds from {@code application.yml}: java.time as ISO text, null fields left out. */
    private static ObjectMapper applicationMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    private static Method handler(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + name));
    }

    private static List<String> texts(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
