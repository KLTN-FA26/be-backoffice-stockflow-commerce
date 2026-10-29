package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.Role;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inventory tools on real stock rows, through the real security chain and a scripted model (design §4.1): a bin a
 * pick emptied is not stock, a SKU nobody has is NOT_FOUND rather than "out of stock", and a lot's holds are the ones
 * that still hold units. {@code InventoryToolsTest} covers how each list is cut and counted.
 */
@SecuredIntegrationTest
class AssistantInventoryToolsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private AlertFixtures fixtures;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("getStockLots leaves out a bin a pick emptied, though its lot expires first, and counts what it lists")
    void emptiedBinIsNotStock() throws Exception {
        String sku = fixtures.sku(null, null);
        LocalDate today = LocalDate.now(ZONE);
        fixtures.stock(sku, AlertFixtures.HCM_BIN, 0, "AVAILABLE", "EMPTIED-1", today.plusDays(20));
        fixtures.stock(sku, AlertFixtures.HCM_BIN_2, 12, "AVAILABLE", "STOCKED-1", today.plusDays(150));
        script(toolUse("getStockLots", "{\"sku\":\"" + sku + "\"}"), answer("Lô STOCKED-1."));

        Map<String, Object> call = askAsPlanner().get(0);

        String result = (String) call.get("result");
        assertThat(call.get("outcome")).isEqualTo("OK");
        assertThat(JsonPath.<List<String>>read(result, "$.locations[*].lotNumber")).containsExactly("STOCKED-1");
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isFalse();
        assertThat(result).doesNotContain("EMPTIED-1");
    }

    @Test
    @DisplayName("a SKU nobody has is NOT_FOUND in both stock tools, never an answer of zero stock")
    void unknownSkuIsNotFound() throws Exception {
        String unknown = "NOSUCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        script(toolUse("getStockLevels", "{\"sku\":\"" + unknown + "\"}"),
                toolUse("getStockLots", "{\"sku\":\"" + unknown + "\"}"),
                answer("Không tìm thấy mã " + unknown + "."));

        List<Map<String, Object>> calls = askAsPlanner();

        assertThat(calls).extracting(call -> call.get("tool_name")).containsExactly("getStockLevels", "getStockLots");
        assertThat(calls).extracting(call -> call.get("outcome")).containsOnly("NOT_FOUND");
        assertThat(calls).allSatisfy(call -> assertThat((String) call.get("result")).startsWith("NOT_FOUND")
                .contains(unknown).doesNotContain("companyAvailableToPromise", "locations"));
    }

    @Test
    @DisplayName("traceLot lists the holds that still hold units, not the released ones, and counts each list")
    void traceLotKeepsLiveHolds() throws Exception {
        String sku = fixtures.sku(null, null);
        String lot = "TRACE-" + sku.substring(sku.length() - 8);
        LocalDate expiry = LocalDate.now(ZONE).plusDays(90);
        UUID released = fixtures.stock(sku, AlertFixtures.HCM_BIN, 10, "AVAILABLE", lot, expiry);
        UUID held = fixtures.stock(sku, AlertFixtures.HCM_BIN_2, 10, "AVAILABLE", lot, expiry);
        fixtures.hold(released, 2);
        fixtures.release(released);
        fixtures.hold(held, 3);
        script(toolUse("traceLot", "{\"lotNumber\":\"" + lot + "\",\"sku\":\"" + sku + "\"}"), answer("Đang giữ 3."));

        Map<String, Object> call = askAsPlanner().get(0);

        String result = (String) call.get("result");
        assertThat(call.get("outcome")).isEqualTo("OK");
        assertThat(JsonPath.<Integer>read(result, "$.totalLots")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].holdsTotal")).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(result, "$.lots[0].holds[*].quantity")).containsExactly(3);
        assertThat(JsonPath.<Boolean>read(result, "$.lots[0].holdsTruncated")).isFalse();
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].balancesTotal")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].ordersTotal")).isZero();
        assertThat(result).doesNotContain("RELEASED");
    }

    // ------------------------------------------------------------------------------------------------------------

    /** An INVENTORY_PLANNER asks one question; the scripted model makes its calls. Returns them, in order. */
    private List<Map<String, Object>> askAsPlanner() throws Exception {
        UUID planner = fixtures.staff("INVENTORY_PLANNER");
        String body = http.asRole(Role.INVENTORY_PLANNER, planner, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversation = JsonPath.read(body, "$.data.id");
        http.asRole(Role.INVENTORY_PLANNER, planner, post(BASE + "/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Con bao nhieu?\"}"))
                .andExpect(status().isOk());
        return jdbc.queryForList("select tool_name, outcome, result from assistant.tool_call "
                + "where conversation_id = ?::uuid order by id", conversation);
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
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
