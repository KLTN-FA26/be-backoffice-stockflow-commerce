package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a client of the assistant may retry, and how, with the real security chain, the real idempotency filter and a
 * scripted model: an {@code Idempotency-Key} works on the plain endpoint and is refused on the stream, and a provider
 * failure says whether asking again can help.
 */
@SecuredIntegrationTest
@TestPropertySource(properties = "stockflow.idempotency.enabled=true")
class AssistantRetryIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("an Idempotency-Key on the stream is refused with 400 before anything runs, whatever the client "
            + "accepts, and the filter neither claims nor stores the key")
    void theStreamRefusesAKey() throws Exception {
        UUID planner = staff();
        String conversation = start(planner);

        for (String accept : List.of(MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE)) {
            String key = "assistant-stream-" + UUID.randomUUID();
            http.asRole(Role.INVENTORY_PLANNER, planner, question(BASE + "/" + conversation + "/messages/stream")
                            .header("Idempotency-Key", key).accept(accept))
                    .andExpect(status().isBadRequest())
                    .andExpect(request().asyncNotStarted())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));

            assertThat(jdbc.queryForObject("select count(*) from platform.idempotency_record where idempotency_key = ?",
                    Integer.class, key)).as("nothing claimed or stored for Accept %s", accept).isZero();
        }
        verify(model, never()).next(any());
        assertThat(jdbc.queryForObject("select count(*) from assistant.message where conversation_id = ?::uuid",
                Integer.class, conversation)).as("no question was stored").isZero();
    }

    @Test
    @DisplayName("the plain endpoint still honours the key: the retry gets the stored answer, the model is asked once")
    void thePlainEndpointReplays() throws Exception {
        UUID planner = staff();
        String conversation = start(planner);
        when(model.next(any())).thenReturn(answer("Xin chào, tôi giúp được gì?"));
        String key = "assistant-ask-" + UUID.randomUUID();

        String first = http.asRole(Role.INVENTORY_PLANNER, planner, question(BASE + "/" + conversation + "/messages")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        http.asRole(Role.INVENTORY_PLANNER, planner, question(BASE + "/" + conversation + "/messages")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.messageId").value(JsonPath.<String>read(first, "$.data.messageId")));

        verify(model, times(1)).next(any());
    }

    @Test
    @DisplayName("a spent provider quota is 429 ASSISTANT_QUOTA_EXHAUSTED and the key is released: the same retry asks "
            + "again once the quota is back")
    void aSpentQuotaIsNotKeptAgainstTheKey() throws Exception {
        UUID planner = staff();
        String conversation = start(planner);
        when(model.next(any()))
                .thenThrow(new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED,
                        "The model provider's quota is used up; the assistant answers again once it is renewed"))
                .thenReturn(answer("Xin chào."));
        String key = "assistant-quota-" + UUID.randomUUID();

        http.asRole(Role.INVENTORY_PLANNER, planner, question(BASE + "/" + conversation + "/messages")
                        .header("Idempotency-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_QUOTA_EXHAUSTED"));
        http.asRole(Role.INVENTORY_PLANNER, planner, question(BASE + "/" + conversation + "/messages")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.answer").value("Xin chào."));
    }

    @Test
    @DisplayName("on the stream, a refused key or model is an error event ASSISTANT_MISCONFIGURED, in the user's "
            + "language")
    void theStreamSaysWhoMustAct() throws Exception {
        UUID planner = staff();
        String conversation = start(planner);
        when(model.next(any())).thenThrow(new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED,
                "The model provider answered 401"));

        MvcResult started = http.asRole(Role.INVENTORY_PLANNER, planner,
                        question(BASE + "/" + conversation + "/messages/stream")
                                .accept(MediaType.TEXT_EVENT_STREAM).header("Accept-Language", "vi"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(30_000);

        assertThat(started.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:error", "\"errorCode\":\"ASSISTANT_MISCONFIGURED\"", "báo quản trị")
                .doesNotContain("answered 401");
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID staff() {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'INVENTORY_PLANNER'""",
                user));
        return user;
    }

    private String start(UUID user) throws Exception {
        String body = http.asRole(Role.INVENTORY_PLANNER, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder question(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Xin chào\"}");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
