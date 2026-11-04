package com.stockflow.assistant;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What comes with an answer beside its text (design §7), end to end with the real security chain and migrations: the
 * table of rows its lookup returned, the follow-up questions the asker may use, the numbers no lookup holds — the same
 * in the live answer, the stream and the reopened transcript — and the verdict its owner gives it, which nobody else
 * can give or see.
 */
@SecuredIntegrationTest
class AssistantAnswerExtrasIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final String BIN = "HCM-A01-1-B";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private String sku;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        // Letters only: a digit in the SKU would be a number the question holds, and could ground the answer's.
        sku = "AXT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT).chars()
                .map(c -> Character.isDigit(c) ? 'G' + (c - '0') : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Assistant extras fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Assistant extras fixture");
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        });
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("an answer carries the rows of its lookup as a table, the next question its asker may use, and the "
            + "number no lookup holds; the reopened transcript shows the same, with no verdict yet")
    void tablesSuggestionsAndUnverifiedNumbers() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        // 250: above any day, month or minute the question's calendar holds, and in no result.
        script(toolUse("getStockLevels", "{\"sku\":\"" + sku + "\"}"), answer("Kho HCM còn 10 cái, 250 cái đang về."));
        String conversation = start(Role.INVENTORY_PLANNER, planner);
        String lots = "Mã " + sku + " có những lô nào, hết hạn ngày nào, đang nằm ở vị trí nào?";

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Còn bao nhiêu " + sku + "?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tables.length()").value(1))
                .andExpect(jsonPath("$.data.tables[0].key").value("getStockLevels.warehouses"))
                .andExpect(jsonPath("$.data.tables[0].tool").value("getStockLevels"))
                .andExpect(jsonPath("$.data.tables[0].rows[0].sku").value(sku))
                .andExpect(jsonPath("$.data.tables[0].rows[0].warehouseCode").value("HCM"))
                .andExpect(jsonPath("$.data.tables[0].rows[0].onHand").value(10))
                .andExpect(jsonPath("$.data.tables[0].total").value(1))
                .andExpect(jsonPath("$.data.tables[0].truncated").value(false))
                .andExpect(jsonPath("$.data.suggestions[0]").value(lots))
                .andExpect(jsonPath("$.data.unverifiedNumbers").value(contains("250")));

        String transcript = http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + conversation + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].tables[0].rows[0].onHand").value(10))
                .andExpect(jsonPath("$.data[1].suggestions[0]").value(lots))
                .andExpect(jsonPath("$.data[1].unverifiedNumbers").value(contains("250")))
                .andExpect(jsonPath("$.data[0].tables").isEmpty())
                .andExpect(jsonPath("$.data[0].suggestions").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<Map<String, Object>>read(transcript, "$.data[1]"))
                .as("feedback is always there, null until given").containsEntry("feedback", null);
    }

    @Test
    @DisplayName("a streamed answer carries the same table and follow-ups, mapped on the worker thread")
    void streamedAnswerCarriesTheExtras() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        script(toolUse("getStockLevels", "{\"sku\":\"" + sku + "\"}"), answer("Kho HCM còn 10."));
        String conversation = start(Role.INVENTORY_PLANNER, planner);

        MvcResult started = http.asRole(Role.INVENTORY_PLANNER, planner,
                        post(BASE + "/" + conversation + "/messages/stream").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"Còn bao nhiêu " + sku + "?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(30_000);
        String events = started.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(events).contains("event:answer", "\"key\":\"getStockLevels.warehouses\"",
                "\"suggestions\":[\"Mã " + sku + " có những lô nào", "\"unverifiedNumbers\":[]");
    }

    @Test
    @DisplayName("an owner rates an answer, changes their mind, and the transcript shows the latest verdict; one row "
            + "per answer, the reason stripped")
    void ownerRatesAnAnswer() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        Exchange exchange = answered(planner);

        http.asRole(Role.INVENTORY_PLANNER, planner, rate(exchange, "{\"verdict\":\"UP\"}"))
                .andExpect(status().isNoContent());
        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + exchange.conversation() + "/messages"))
                .andExpect(jsonPath("$.data[1].feedback").value("UP"));

        http.asRole(Role.INVENTORY_PLANNER, planner, rate(exchange,
                        "{\"verdict\":\"DOWN\",\"reason\":\"  Số tồn của kho EVN không đúng  \"}"))
                .andExpect(status().isNoContent());
        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + exchange.conversation() + "/messages"))
                .andExpect(jsonPath("$.data[1].feedback").value("DOWN"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select verdict, reason, owner_user_id from assistant.answer_feedback where message_id = ?::uuid",
                exchange.answer());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("verdict")).isEqualTo("DOWN");
            assertThat(row.get("reason")).isEqualTo("Số tồn của kho EVN không đúng");
            assertThat(row.get("owner_user_id")).isEqualTo(planner);
        });
    }

    @Test
    @DisplayName("only an answer of one's own conversation takes a verdict: a question, an unknown id and another "
            + "person's answer are not found, and nothing is written")
    void onlyOwnAnswers() throws Exception {
        UUID owner = staff("INVENTORY_PLANNER");
        UUID other = staff("INVENTORY_PLANNER");
        Exchange exchange = answered(owner);
        String up = "{\"verdict\":\"UP\"}";

        http.asRole(Role.INVENTORY_PLANNER, owner, rate(exchange.conversation(), exchange.question(), up))
                .andExpect(status().isNotFound());
        http.asRole(Role.INVENTORY_PLANNER, owner, rate(exchange.conversation(), UUID.randomUUID().toString(), up))
                .andExpect(status().isNotFound());
        http.asRole(Role.INVENTORY_PLANNER, other, rate(exchange, up))
                .andExpect(status().isNotFound());
        String othersConversation = start(Role.INVENTORY_PLANNER, other);
        http.asRole(Role.INVENTORY_PLANNER, other, rate(othersConversation, exchange.answer(), up))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from assistant.answer_feedback where message_id = ?::uuid",
                Long.class, exchange.answer())).isZero();
    }

    @Test
    @DisplayName("a verdict is UP or DOWN with at most 500 characters of reason; a portal customer is refused")
    void validationAndCustomers() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        Exchange exchange = answered(planner);

        http.asRole(Role.INVENTORY_PLANNER, planner, rate(exchange, "{\"verdict\":\"MEH\"}"))
                .andExpect(status().isBadRequest());
        http.asRole(Role.INVENTORY_PLANNER, planner, rate(exchange, "{\"reason\":\"no verdict\"}"))
                .andExpect(status().isBadRequest());
        http.asRole(Role.INVENTORY_PLANNER, planner, rate(exchange,
                        "{\"verdict\":\"DOWN\",\"reason\":" + TextNode.valueOf("x".repeat(501)) + "}"))
                .andExpect(status().isBadRequest());
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, rate(exchange, "{\"verdict\":\"UP\"}")).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from assistant.answer_feedback where message_id = ?::uuid",
                Long.class, exchange.answer())).isZero();
    }

    // ------------------------------------------------------------------------------------------------------------

    /** A conversation of {@code user} with one question answered from a tool. */
    private Exchange answered(UUID user) throws Exception {
        script(toolUse("getStockLevels", "{\"sku\":\"" + sku + "\"}"), answer("Kho HCM còn 10."));
        String conversation = start(Role.INVENTORY_PLANNER, user);
        http.asRole(Role.INVENTORY_PLANNER, user, ask(conversation, "Còn bao nhiêu " + sku + "?"))
                .andExpect(status().isOk());
        String transcript = http.asRole(Role.INVENTORY_PLANNER, user, get(BASE + "/" + conversation + "/messages"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Exchange(conversation, JsonPath.read(transcript, "$.data[0].messageId"),
                JsonPath.read(transcript, "$.data[1].messageId"));
    }

    private record Exchange(String conversation, String question, String answer) {
    }

    private static MockHttpServletRequestBuilder rate(Exchange exchange, String body) {
        return rate(exchange.conversation(), exchange.answer(), body);
    }

    private static MockHttpServletRequestBuilder rate(String conversation, String message, String body) {
        return post(BASE + "/" + conversation + "/messages/" + message + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + TextNode.valueOf(text) + "}");
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
