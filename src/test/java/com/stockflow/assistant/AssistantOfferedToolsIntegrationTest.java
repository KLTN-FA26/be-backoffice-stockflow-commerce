package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the model is offered, against the seeded role matrix, the real permission aspect and the real tools (ADR-0011,
 * amended 29/10/2026; design §5.7): each persona is offered exactly the tools the panel says it may use, sorted by
 * name and the same on every call of an answer, and a tool it was not offered still refuses it, through its own guard,
 * when a model calls it anyway.
 *
 * <p>The expected lists are read from {@code GET /api/v1/assistant/capabilities} rather than written out: that the
 * panel matches the seeded grants is {@code AssistantCapabilitiesIntegrationTest}'s to hold, and a tool added later is
 * covered here without an edit.</p>
 */
@SecuredIntegrationTest
class AssistantOfferedToolsIntegrationTest {

    private static final String CAPABILITIES = "/api/v1/assistant/capabilities";
    private static final String CONVERSATIONS = "/api/v1/assistant/conversations";

    /**
     * The tools that read stock figures or cartons: none is offered to a persona without a stock permission. Since the
     * rescope there are no low-stock, expiry or reorder tools left among them.
     */
    /**
     * The tools reading stock figures, which Sales never get. traceLot is not one of them any more: its report has its own
     * guard since V20261209000300 ({@code inventory-lot-traces}), granted to Sales, who answer customers about a lot.
     */
    private static final Set<String> STOCK_TOOLS = Set.of("getStockLevels", "getStockLots", "lookupScan",
            "investigateCarton");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;

    /** A seeded role, assigned to warehouse HCM or to no warehouse. */
    record Persona(Role role, boolean atHcm) {

        @Override
        public String toString() {
            return role + (atHcm ? " [HCM]" : "");
        }
    }

    /**
     * The personas of {@code AssistantCapabilitiesIntegrationTest}: every seeded role that may open the assistant,
     * each once, the warehouse roles at HCM since their scope is WAREHOUSE.
     */
    static Stream<Persona> personas() {
        return Stream.of(new Persona(Role.SYSTEM_ADMIN, false), new Persona(Role.WAREHOUSE_MANAGER, true),
                // V20270106000500: warehouse staff may open the assistant too.
                new Persona(Role.WAREHOUSE_STAFF, true), new Persona(Role.SALES_STAFF, false),
                new Persona(Role.SALES_MANAGER, false), new Persona(Role.ACCOUNTANT, false),
                new Persona(Role.PRODUCTION_COORDINATOR, false));
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("personas")
    @DisplayName("each seeded persona is offered exactly the tools the panel grants it, sorted by name, and the same "
            + "list on every call of the answer")
    void offeredWhatThePanelGrants(Persona persona) throws Exception {
        UUID user = staff(persona);
        List<String> granted = granted(persona.role(), user);
        // searchKnowledge is open to every persona: one tool round, so the answer takes two model calls.
        when(model.next(any())).thenReturn(toolUse("searchKnowledge", "{\"query\":\"custom printed return\"}"),
                answer("Hàng in theo thiết kế chỉ đổi khi lỗi."));
        String conversation = start(persona.role(), user);

        http.asRole(persona.role(), user, ask(conversation, "Hàng in theo thiết kế có được đổi trả không?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("searchKnowledge"));

        List<LanguageModel.Request> requests = requests();
        assertThat(requests).hasSize(2);
        assertThat(names(requests.getFirst())).isEqualTo(granted).isSorted();
        assertThat(requests.get(1).tools()).as("byte for byte the same list on the next call")
                .isEqualTo(requests.getFirst().tools());
    }

    @Test
    @DisplayName("Sales are offered no stock tool; a model that calls one anyway is refused by that tool's guard, "
            + "naming the permission, and the refusal is recorded like any call")
    void notOfferedIsStillRefused() throws Exception {
        UUID sales = staff(new Persona(Role.SALES_STAFF, false));
        when(model.next(any())).thenReturn(toolUse("getStockLevels", "{\"sku\":\"SOFA-3S-GREY\"}"),
                answer("Chưa xem được tồn kho."));
        String conversation = start(Role.SALES_STAFF, sales);

        http.asRole(Role.SALES_STAFF, sales, ask(conversation, "Còn bao nhiêu SOFA-3S-GREY?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("getStockLevels"))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        assertThat(requests()).isNotEmpty().allSatisfy(request -> assertThat(names(request))
                .doesNotContainAnyElementsOf(STOCK_TOOLS));
        String result = jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid",
                String.class, conversation);
        assertThat(result).startsWith("NOT_PERMITTED").contains("inventory-stock-items:READ");
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID staff(Persona persona) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""",
                    user, persona.role().name());
            if (persona.atHcm()) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, DemoData.WAREHOUSE_HCM);
            }
        });
        return user;
    }

    /** The names the panel marks as granted, in the panel's order (by name). */
    private List<String> granted(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, get(CAPABILITIES)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> granted = new ArrayList<>();
        for (JsonNode tool : json.readTree(body).path("data").path("tools")) {
            if (tool.path("granted").asBoolean()) {
                granted.add(tool.path("name").asText());
            }
        }
        return granted;
    }

    /** Every request the model was sent in this test, in order. */
    private List<LanguageModel.Request> requests() {
        ArgumentCaptor<LanguageModel.Request> requests = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model, atLeastOnce()).next(requests.capture());
        return requests.getAllValues();
    }

    private static List<String> names(LanguageModel.Request request) {
        return request.tools().stream().map(ToolSpec::name).toList();
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(CONVERSATIONS)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(CONVERSATIONS + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private static LanguageModel.Reply toolUse(String tool, String arguments) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                List.of(new ToolCall("toolu_" + UUID.randomUUID(), tool, arguments))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
