package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeParser;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.service.KnowledgeService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retrieval end to end (design §6): the documents indexed at start-up, searched in PostgreSQL, and cited in an
 * answer — a cited anchor is reported only when the answer names it and the search returned it.
 */
@SecuredIntegrationTest
class AssistantKnowledgeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void indexed() {
        // The start-up index ran when the context started; this makes the test independent of event order.
        knowledge.index();
    }

    @Test
    @DisplayName("every rule, term and state machine is indexed, and indexing again changes nothing")
    void indexedOnce() {
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where source = "
                + "'05-business-rules'", Long.class)).isGreaterThan(50);
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where kind = 'TERM'",
                Long.class)).isGreaterThan(5);
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where kind = "
                + "'STATE_MACHINE'", Long.class)).isGreaterThan(10);
        assertThat(knowledge.index()).as("an unchanged document writes nothing").isZero();
    }

    @Test
    @DisplayName("the process flows are indexed and found by Vietnamese words, with or without marks, and by their code")
    void flowsAreFound() {
        assertThat(jdbc.queryForObject("select count(distinct anchor) from assistant.knowledge_chunk where kind = "
                + "'FLOW' and source = '04c-all-flows' and anchor not like '%(%' and anchor not like '%›%'", Long.class))
                // One anchor per flow code of 04c; X-DMG (damaged goods) came with develop on 5/11.
                .isEqualTo(26);
        assertThat(knowledge.search("quy trình đơn mua hàng gửi nhà cung cấp duyệt", 5))
                .extracting(KnowledgeChunk::anchor).contains("F-PO");
        assertThat(knowledge.search("nhat thieu hang don dung lai", 5)).extracting(KnowledgeChunk::anchor)
                .contains("X-SHORT");
        assertThat(knowledge.search("X-RESV", 5)).first().extracting(KnowledgeChunk::anchor).isEqualTo("X-RESV");
    }

    @Test
    @DisplayName("an older build does not put its rules back over a newer build's index")
    void olderBuildLeavesNewerIndexAlone() {
        Instant newer = Instant.parse("2099-01-01T00:00:00Z");
        List<KnowledgeChunk> newRules = List.of(new KnowledgeChunk("eval-source", "BR-EVL-001",
                KnowledgeChunk.Kind.RULE, "BR-EVL-001 — Eval", "The newer wording."));
        List<KnowledgeChunk> oldRules = List.of(new KnowledgeChunk("eval-source", "BR-EVL-001",
                KnowledgeChunk.Kind.RULE, "BR-EVL-001 — Eval", "The older wording."));

        assertThat(tx.execute(s -> repository.sync("eval-source", newRules, newer)).changes()).isEqualTo(1);
        KnowledgeRepository.Sync older = tx.execute(s -> repository.sync("eval-source", oldRules,
                newer.minusSeconds(3600)));

        assertThat(older.newerIndexed()).isTrue();
        assertThat(jdbc.queryForObject("select body from assistant.knowledge_chunk where source = 'eval-source'",
                String.class)).isEqualTo("The newer wording.");
        // A document missing from a build is emptied, not left to be cited: the same build syncs it to nothing.
        assertThat(tx.execute(s -> repository.sync("eval-source", List.of(), newer)).changes()).isEqualTo(1);
        tx.executeWithoutResult(s -> jdbc.update("delete from assistant.knowledge_source where source = 'eval-source'"));
    }

    @Test
    @DisplayName("a long state machine is found by the part that answers, not cut off at its start")
    void stateMachinePassages() {
        assertThat(knowledge.search("who may cancel order until when", 5)).extracting(KnowledgeChunk::anchor)
                .anySatisfy(anchor -> assertThat(anchor).startsWith("9. Order — order-service"));
        assertThat(jdbc.queryForObject("select max(length(body)) from assistant.knowledge_chunk where kind = "
                + "'STATE_MACHINE'", Integer.class)).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
    }

    @Test
    @DisplayName("a search finds the rule by its words, by its anchor, and ignores accents and case")
    void search() {
        assertThat(knowledge.search("custom printed returnable defective", 5)).extracting(KnowledgeChunk::anchor)
                .contains("BR-RMA-002");
        assertThat(knowledge.search("what does br-rma-002 say", 5).getFirst().anchor()).isEqualTo("BR-RMA-002");
        assertThat(knowledge.search("RESERVED allocated", 5)).extracting(KnowledgeChunk::anchor)
                .contains("Reserved");
        assertThat(knowledge.search("!!! ??", 5)).isEmpty();
    }

    @Test
    @DisplayName("an answer cites what it names and the search returned; a rule it names but never retrieved is not")
    void citations() throws Exception {
        SecuredMvc http = new SecuredMvc(mockMvc, decoder);
        UUID seller = tx.execute(s -> ReferenceRows.user(entityManager));
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{}", call.getArgument(0),
                List.of()));
        when(model.next(any())).thenReturn(
                new LanguageModel.Reply(new Turn.ModelTurn("{}", null, List.of(new ToolCall("toolu_k",
                        "searchKnowledge", "{\"query\":\"custom printed returnable defective\"}"))),
                        LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(10, 5, 0, 0), "scripted"),
                new LanguageModel.Reply(new Turn.ModelTurn("{}", "Không, trừ khi bị lỗi (BR-RMA-002). "
                        + "Xem thêm BR-ZZZ-999.", List.of()),
                        LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(10, 5, 0, 0), "scripted"));
        String conversation = JsonPath.read(http.asRole(Role.SALES_STAFF, seller,
                post("/api/v1/assistant/conversations")).andReturn().getResponse().getContentAsString(), "$.data.id");

        http.asRole(Role.SALES_STAFF, seller, post("/api/v1/assistant/conversations/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hàng in theo yêu cầu có được trả lại không?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("searchKnowledge"))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0]").value("BR-RMA-002"));
    }
}
