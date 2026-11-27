package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.KnowledgeService;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract §4.1 and §5 with the real security chain, the real permission aspect, the seeded roles and the migrated
 * schema: the error explanation reads its rules from the knowledge index; the review of answers marked wrong is the
 * System Admin's alone and turns each into a golden-set case; the usage counts are the System Admin's and the Sales
 * Manager's and hold no user.
 */
@SecuredIntegrationTest
class AssistantReviewIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeService knowledge;
    @Autowired FeatureUse featureUse;

    private SecuredMvc http;
    private StaffFixtures staff;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        staff = new StaffFixtures(jdbc, tx, entityManager);
        knowledge.index();
    }

    // ---- 4.1 error explanations

    @Test
    @DisplayName("a refused user reads the rules behind the refusal from the index, in Vietnamese, with next steps; an "
            + "unmapped code gives its message alone; a value that is no code is 404")
    void errorExplanation() throws Exception {
        UUID warehouse = staff.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);

        JsonNode explained = data(http.asRole(Role.WAREHOUSE_MANAGER, warehouse,
                        get("/api/v1/assistant/error-explanations/LOAD_HANDOVER_BLOCKED")
                                .header("Accept-Language", "vi"))
                .andExpect(status().isOk()));
        assertThat(explained.path("errorCode").asText()).isEqualTo("LOAD_HANDOVER_BLOCKED");
        assertThat(explained.path("message").asText()).isEqualTo("Có đơn hàng trên xe chưa được phép bàn giao");
        assertThat(explained.path("rules").findValuesAsText("ruleId")).contains("09 BR-18", "15 BR-08");
        JsonNode payment = explained.path("rules").get(2);
        assertThat(payment.path("chapter").asText()).isEqualTo("15-payment");
        assertThat(payment.path("text").asText()).contains("BEFORE_DELIVERY");
        assertThat(payment.path("href").isNull()).isTrue();
        assertThat(explained.path("nextSteps").size()).isBetween(1, 3);

        JsonNode plain = data(http.asRole(Role.WAREHOUSE_MANAGER, warehouse,
                        get("/api/v1/assistant/error-explanations/CATALOG_SLUG_EXISTS"))
                .andExpect(status().isOk()));
        assertThat(plain.path("message").asText()).isNotBlank();
        assertThat(plain.path("rules").isEmpty()).isTrue();
        assertThat(plain.path("nextSteps").isEmpty()).isTrue();

        http.asRole(Role.WAREHOUSE_MANAGER, warehouse, get("/api/v1/assistant/error-explanations/NOT_A_CODE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the explanation is the panel's: a portal customer is refused")
    void errorExplanationIsBackOffice() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, get("/api/v1/assistant/error-explanations/QUOTE_EXPIRED"))
                .andExpect(status().isForbidden());
    }

    // ---- 5.1 answers marked wrong

    @Test
    @DisplayName("the System Admin reads every answer marked down, with the question, the tools, the comment and the "
            + "rater's role and warehouse, and a golden case to paste; nobody else reads them")
    void markedAnswers() throws Exception {
        UUID manager = staff.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        Exchange exchange = exchange(manager, "Tồn EVAL-REVIEW-1 ở HCM bao nhiêu?", "Còn 999 thùng.");
        http.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/assistant/conversations/" + exchange.conversation()
                        + "/messages/" + exchange.answer() + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"DOWN\",\"reason\":\"Sai số tồn\"}"))
                .andExpect(status().isNoContent());
        UUID admin = staff.staff("SYSTEM_ADMIN");
        String today = LocalDate.now(VIETNAM).toString();

        JsonNode page = data(http.asRole(Role.SYSTEM_ADMIN, admin,
                        get("/api/v1/assistant/feedback").param("from", today).param("to", today).param("size", "50"))
                .andExpect(status().isOk()));
        JsonNode item = null;
        for (JsonNode candidate : page.path("items")) {
            if (candidate.path("messageId").asText().equals(exchange.answer().toString())) {
                item = candidate;
            }
        }
        assertThat(item).as("the answer marked down is listed").isNotNull();
        assertThat(item.path("verdict").asText()).isEqualTo("DOWN");
        assertThat(item.path("comment").asText()).isEqualTo("Sai số tồn");
        assertThat(item.path("question").asText()).isEqualTo("Tồn EVAL-REVIEW-1 ở HCM bao nhiêu?");
        assertThat(item.path("answer").asText()).isEqualTo("Còn 999 thùng.");
        assertThat(item.path("raterRoles").toString()).isEqualTo("[\"WAREHOUSE_MANAGER\"]");
        assertThat(item.path("raterWarehouses").toString()).isEqualTo("[\"HCM\"]");
        assertThat(item.path("tools").get(0).path("tool").asText()).isEqualTo("getStockLevels");
        assertThat(item.path("tools").get(0).path("arguments").path("sku").asText()).isEqualTo("EVAL-REVIEW-1");
        JsonNode golden = item.path("goldenCase");
        assertThat(golden.at("/persona/role").asText()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(golden.at("/oracle/tool").asText()).isEqualTo("getStockLevels");
        assertThat(golden.path("useCase").asText()).isEqualTo("TODO");
        assertThat(item.toString()).as("who rated is a role, never a person").doesNotContain(manager.toString());

        JsonNode ups = data(http.asRole(Role.SYSTEM_ADMIN, admin,
                        get("/api/v1/assistant/feedback").param("verdict", "UP").param("from", today)
                                .param("to", today))
                .andExpect(status().isOk()));
        assertThat(ups.path("items").findValuesAsText("messageId")).doesNotContain(exchange.answer().toString());

        http.asRole(Role.SYSTEM_ADMIN, admin, get("/api/v1/assistant/feedback").param("size", "51"))
                .andExpect(status().isBadRequest());
        for (Role refused : new Role[] {Role.SALES_MANAGER, Role.WAREHOUSE_MANAGER, Role.ACCOUNTANT}) {
            UUID someone = staff.staff(refused.name());
            http.asRole(refused, someone, get("/api/v1/assistant/feedback")).andExpect(status().isForbidden());
        }
    }

    // ---- 5.2 usage

    @Test
    @DisplayName("a use of the explanation is counted per feature, role and day, with no user; the Sales Manager reads "
            + "the counts, Sales do not; a period backwards or longer than 92 days is refused")
    void usage() throws Exception {
        UUID sales = staff.staff("SALES_STAFF");
        http.asRole(Role.SALES_STAFF, sales, get("/api/v1/assistant/error-explanations/QUOTE_EXPIRED"))
                .andExpect(status().isOk());
        featureUse.flush();
        String today = LocalDate.now(VIETNAM).toString();
        UUID manager = staff.staff("SALES_MANAGER");

        JsonNode usage = data(http.asRole(Role.SALES_MANAGER, manager,
                        get("/api/v1/assistant/usage").param("from", today).param("to", today))
                .andExpect(status().isOk()));
        assertThat(usage.path("featureKeys").toString()).contains("\"error-explanation\"", "\"chat\"");
        boolean counted = false;
        for (JsonNode row : usage.path("rows")) {
            if (row.path("featureKey").asText().equals("error-explanation")
                    && row.path("role").asText().equals("SALES_STAFF")) {
                counted = row.path("uses").asLong() >= 1;
            }
        }
        assertThat(counted).as("the explanation Sales read is counted").isTrue();
        assertThat(usage.toString()).doesNotContain(sales.toString());
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'assistant' AND table_name = 'feature_use'""", String.class))
                .as("no user, no content").containsExactlyInAnyOrder("feature_key", "role", "day", "uses");

        http.asRole(Role.SALES_STAFF, sales, get("/api/v1/assistant/usage")).andExpect(status().isForbidden());
        UUID admin = staff.staff("SYSTEM_ADMIN");
        http.asRole(Role.SYSTEM_ADMIN, admin, get("/api/v1/assistant/usage").param("from", "2026-01-01")
                        .param("to", "2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        http.asRole(Role.SYSTEM_ADMIN, admin, get("/api/v1/assistant/usage").param("from", "2026-02-02")
                        .param("to", "2026-02-01"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Exchange(UUID conversation, UUID answer) {
    }

    /** A conversation of {@code owner}: a question, a turn that called getStockLevels, and the answer. */
    private Exchange exchange(UUID owner, String question, String answer) {
        UUID conversation = Identifiers.newId();
        UUID asked = Identifiers.newId();
        UUID calling = Identifiers.newId();
        UUID answered = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO assistant.conversation (id, owner_user_id, title, message_count, last_activity_at)
                    VALUES (?, ?, 'Tồn kho', 3, NOW())""", conversation, owner);
            jdbc.update("""
                    INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content)
                    VALUES (?, ?, 0, 'USER', ?, '{}'::jsonb)""", asked, conversation, question);
            jdbc.update("""
                    INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content, stop_reason, model)
                    VALUES (?, ?, 1, 'ASSISTANT', NULL, '{}'::jsonb, 'TOOL_USE', 'test-model')""", calling,
                    conversation);
            jdbc.update("""
                    INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content, stop_reason, model)
                    VALUES (?, ?, 2, 'ASSISTANT', ?, '{}'::jsonb, 'END_TURN', 'test-model')""", answered,
                    conversation, answer);
            jdbc.update("""
                    INSERT INTO assistant.tool_call (id, conversation_id, message_id, call_id, tool_name, arguments,
                                                     outcome, result, duration_ms, channel, user_id)
                    VALUES (?, ?, ?, 'call-1', 'getStockLevels', '{"sku":"EVAL-REVIEW-1"}'::jsonb, 'OK', '{}', 5,
                            'CHAT', ?)""", Identifiers.newId(), conversation, calling, owner);
        });
        return new Exchange(conversation, answered);
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }
}
