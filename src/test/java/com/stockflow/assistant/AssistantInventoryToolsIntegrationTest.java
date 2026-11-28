package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.Role;
import com.stockflow.support.AssistantStock;
import com.stockflow.support.DemoData;
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

import java.time.Duration;
import java.time.Instant;
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
 * The inventory tools on real cartons, through the real security chain and a scripted model (design §4.1): lots in
 * picking order, first in first out, a SKU nobody has is NOT_FOUND rather than "out of stock", and a lot's trace with
 * its balances, its producer's order and no holds it does not have. {@code InventoryToolsTest} covers how each list is cut and counted.
 */
@SecuredIntegrationTest
class AssistantInventoryToolsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private AssistantStock fixtures;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        fixtures = new AssistantStock(jdbc, tx, entityManager);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("getStockLots lists the lots of whole cartons, the oldest received first, and counts what it lists")
    void lotsFirstInFirstOut() throws Exception {
        String sku = fixtures.sku();
        Instant now = Instant.now();
        fixtures.cartons(sku, AssistantStock.HCM_BIN, 2, 50, "NEW-1", now.minus(Duration.ofDays(1)));
        fixtures.cartons(sku, AssistantStock.HCM_BIN_2, 1, 50, "OLD-1", now.minus(Duration.ofDays(30)));
        script(toolUse("getStockLots", "{\"sku\":\"" + sku + "\"}"), answer("Lô OLD-1 xuất trước."));

        Map<String, Object> call = askAsManager().get(0);

        String result = (String) call.get("result");
        assertThat(call.get("outcome")).isEqualTo("OK");
        assertThat(JsonPath.<List<String>>read(result, "$.locations[*].lotNumber")).as("one row per carton")
                .containsExactly("OLD-1", "NEW-1", "NEW-1");
        assertThat(JsonPath.<List<Integer>>read(result, "$.locations[*].onHand")).containsExactly(50, 50, 50);
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isFalse();
        assertThat(result).doesNotContain("expiryDate");
    }

    @Test
    @DisplayName("a SKU nobody has is NOT_FOUND in both stock tools, never an answer of zero stock")
    void unknownSkuIsNotFound() throws Exception {
        String unknown = "NOSUCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        script(toolUse("getStockLevels", "{\"sku\":\"" + unknown + "\"}"),
                toolUse("getStockLots", "{\"sku\":\"" + unknown + "\"}"),
                answer("Không tìm thấy mã " + unknown + "."));

        List<Map<String, Object>> calls = askAsManager();

        assertThat(calls).extracting(call -> call.get("tool_name")).containsExactly("getStockLevels", "getStockLots");
        assertThat(calls).extracting(call -> call.get("outcome")).containsOnly("NOT_FOUND");
        assertThat(calls).allSatisfy(call -> assertThat((String) call.get("result")).startsWith("NOT_FOUND")
                .contains(unknown).doesNotContain("companyAvailableToPromise", "locations"));
    }

    @Test
    @DisplayName("traceLot finds a lot in two bins: each balance, no hold and no order it does not have, and nothing "
            + "of expiry or suppliers")
    void traceLotOfCartons() throws Exception {
        String sku = fixtures.sku();
        String lot = "TRACE-" + sku.substring(sku.length() - 8);
        Instant received = Instant.now().minus(Duration.ofDays(3));
        fixtures.cartons(sku, AssistantStock.HCM_BIN, 1, 10, lot, received);
        fixtures.cartons(sku, AssistantStock.HCM_BIN_2, 2, 10, lot, received);
        script(toolUse("traceLot", "{\"lotNumber\":\"" + lot + "\",\"sku\":\"" + sku + "\"}"),
                answer("Lô ở hai vị trí."));

        Map<String, Object> call = askAsManager().get(0);

        String result = (String) call.get("result");
        assertThat(call.get("outcome")).isEqualTo("OK");
        assertThat(JsonPath.<Integer>read(result, "$.totalLots")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].balancesTotal")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].holdsTotal")).isZero();
        assertThat(JsonPath.<Integer>read(result, "$.lots[0].ordersTotal")).isZero();
        assertThat(result).doesNotContain("expiryDate", "supplierName", "purchaseOrderNumber");
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * A WAREHOUSE_MANAGER of HCM asks one question; the scripted model makes its calls. Returns them, in order.
     */
    private List<Map<String, Object>> askAsManager() throws Exception {
        UUID manager = fixtures.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        String body = http.asRole(Role.WAREHOUSE_MANAGER, manager, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversation = JsonPath.read(body, "$.data.id");
        http.asRole(Role.WAREHOUSE_MANAGER, manager, post(BASE + "/" + conversation + "/messages")
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
