package com.stockflow.assistant;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.service.ConversationService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a question that goes wrong leaves behind, against the real database and security chain and a scripted model
 * (design §3.1): a failure the transcript shows and the daily cap does not count, a conversation that is not left
 * busy when its answer cannot be stored, a tool name the model made up that the audit can still hold, and one
 * answer per question when the transcript is reopened.
 *
 * <p>Declares the same overridden beans as {@code AssistantIntegrationTest}, so the two can share one cached
 * context.</p>
 */
@SecuredIntegrationTest
class AssistantFailureIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ConversationService conversations;

    private SecuredMvc http;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("a question the provider refuses before any reply is shown with the error, and is not counted "
            + "toward the daily cap; one it answers is")
    void failedQuestionIsShownAndNotCounted() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String conversation = start(planner);
        doThrow(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "provider down")).when(model).next(any());

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Lô nào sắp hết hạn?"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_UNAVAILABLE"));

        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + conversation + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[1].text").isNotEmpty());
        assertThat(jdbc.queryForList("select model from assistant.message where conversation_id = ?::uuid "
                + "order by sequence", String.class, conversation))
                .containsExactly(null, "stockflow");
        Instant today = BusinessDate.startOfDay(Instant.now());
        assertThat(conversations.questionsSince(planner, today)).as("the provider never replied").isZero();

        doReturn(answer("Không có lô nào.")).when(model).next(any());
        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Lô nào sắp hết hạn?"))
                .andExpect(status().isOk());
        assertThat(conversations.questionsSince(planner, today)).isEqualTo(1);
    }

    @Test
    @DisplayName("an answer that cannot be stored still releases the conversation: the next question is not refused "
            + "as busy")
    void storeFailureReleasesTheConversation() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String conversation = start(planner);
        // message.model is VARCHAR(64): a provider naming its model longer than that breaks the INSERT at the end.
        LanguageModel.Reply unstorable = new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}",
                "Xin chào.", List.of()), LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, "m".repeat(65));
        doReturn(unstorable, answer("Xin chào.")).when(model).next(any());

        int status = http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Xin chào"))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("the answer could not be stored").isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("select answering_since from assistant.conversation where id = ?::uuid",
                Timestamp.class, conversation)).as("the claim is released").isNull();
        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Xin chào"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Xin chào."));
    }

    @Test
    @DisplayName("a tool name the model made up, which ck_tool_call_name would refuse, is stored under a fixed name "
            + "and the answer is kept")
    void madeUpToolName() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String conversation = start(planner);
        when(model.next(any())).thenReturn(toolUse(null, "default_api.getStockLevels", "{\"sku\":\"AST-NOPE01\"}"),
                answer("Tôi không tra được."));

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Còn bao nhiêu AST-NOPE01?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Tôi không tra được."))
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("unknownTool"))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("INVALID"));

        Map<String, Object> call = jdbc.queryForMap("select tool_name, outcome, result from assistant.tool_call "
                + "where conversation_id = ?::uuid", conversation);
        assertThat(call.get("tool_name")).isEqualTo("unknownTool");
        assertThat(call.get("outcome")).isEqualTo("INVALID");
        assertThat((String) call.get("result")).contains("default_api.getStockLevels");
    }

    @Test
    @DisplayName("reopened, a conversation shows one answer per question, and the tool chips stay under the answer, "
            + "not under the line the model wrote beside its call")
    void oneAnswerPerQuestion() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String conversation = start(planner);
        when(model.next(any())).thenReturn(toolUse("Để tôi kiểm tra tồn kho.", "getStockLevels",
                "{\"sku\":\"AST-NOPE01\"}"), answer("Không thấy mã AST-NOPE01."));

        http.asRole(Role.INVENTORY_PLANNER, planner, ask(conversation, "Còn bao nhiêu AST-NOPE01?"))
                .andExpect(status().isOk());

        http.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + conversation + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].text").value("Không thấy mã AST-NOPE01."))
                .andExpect(jsonPath("$.data[1].toolCalls[0].tool").value("getStockLevels"));
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    private String start(UUID planner) throws Exception {
        String body = http.asRole(Role.INVENTORY_PLANNER, planner, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + TextNode.valueOf(text) + "}");
    }

    private static LanguageModel.Reply toolUse(String text, String tool, String arguments) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", text,
                List.of(new ToolCall("toolu_" + UUID.randomUUID(), tool, arguments))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
