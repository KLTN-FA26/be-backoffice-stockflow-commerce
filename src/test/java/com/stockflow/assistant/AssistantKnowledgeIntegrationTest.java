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
    @DisplayName("every chapter's rules, statuses and flows and the glossary are indexed, the superseded documents are "
            + "not, and indexing again changes nothing")
    void indexedOnce() {
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where source = "
                + "'17-order-management' and kind = 'RULE' and anchor like '17 BR-%'", Long.class)).isGreaterThan(30);
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where kind = 'TERM'",
                Long.class)).isGreaterThan(200);
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where kind = "
                + "'STATE_MACHINE'", Long.class)).isGreaterThan(40);
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where source in "
                + "('05-business-rules', '01-ubiquitous-language', '03-state-machines', '04c-all-flows')", Long.class))
                .as("docs/business-design is superseded: never cited again").isZero();
        assertThat(knowledge.index()).as("an unchanged document writes nothing").isZero();
    }

    @Test
    @DisplayName("the flows are indexed in tests and found by Vietnamese words, with or without marks, and by their "
            + "section")
    void flowsAreFound() {
        assertThat(jdbc.queryForObject("select count(*) from assistant.knowledge_chunk where kind = 'FLOW'",
                Long.class)).isGreaterThan(100);
        assertThat(knowledge.search("đổi trả hàng in theo thiết kế lỗi sản xuất đổi ý", 5))
                .extracting(KnowledgeChunk::anchor).contains("17 §4.10");
        assertThat(knowledge.search("bao thieu treo don kiem ke mu", 5)).extracting(KnowledgeChunk::anchor)
                .contains("07 BR-09");
        assertThat(knowledge.search("17 §4.10", 5)).first().extracting(KnowledgeChunk::anchor).isEqualTo("17 §4.10");
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
    @DisplayName("a long section is found by the part that answers, and naming it brings every part of it")
    void sectionPassages() {
        assertThat(knowledge.search("trạng thái đơn hàng Return Requested Returned", 5))
                .extracting(KnowledgeChunk::anchor).anySatisfy(anchor -> assertThat(anchor).startsWith("17 §5.1"));
        assertThat(knowledge.search("17 §5.1", 5)).extracting(KnowledgeChunk::anchor)
                .contains("17 §5.1", "17 §5.1 (2)", "17 §5.1 (3)", "17 §5.1 (4)");
        assertThat(jdbc.queryForObject("select max(length(body)) from assistant.knowledge_chunk", Integer.class))
                .isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
    }

    @Test
    @DisplayName("a search finds the rule by its words and by its anchor, the chapter deciding which BR-08, and "
            + "ignores accents and case")
    void search() {
        assertThat(knowledge.search("bốn mắt người duyệt sản phẩm người gửi duyệt", 5))
                .extracting(KnowledgeChunk::anchor).contains("01 BR-12");
        assertThat(knowledge.search("15 br-08 nói gì", 5).getFirst().anchor()).isEqualTo("15 BR-08");
        assertThat(knowledge.search("quy dinh 9 BR-8", 5).getFirst().anchor()).isEqualTo("09 BR-08");
        assertThat(knowledge.search("GIỮ HÀNG reservation", 5)).extracting(KnowledgeChunk::anchor)
                .contains("Reservation / Giữ hàng");
        assertThat(knowledge.search("!!! ??", 5)).isEmpty();
    }

    @Test
    @DisplayName("a passage sharing only one word of a longer search is not found by its words; one word alone, a "
            + "rule named, or both words of two still are")
    void oneSharedWordIsNotEnough() {
        assertThat(repository.search("Tet holiday Manhattan", 5, 1)).as("before the threshold: 'Manhattan' alone")
                .isNotEmpty();
        assertThat(repository.search("Tet holiday Manhattan", 5, 2)).isEmpty();
        assertThat(knowledge.search("Tet holiday Manhattan", 5)).as("as the assistant is configured").isEmpty();
        assertThat(repository.search("FEFO", 5, 2)).as("a one-word search needs its one word").isNotEmpty();
        assertThat(repository.search("15 BR-08 holiday", 5, 2)).extracting(KnowledgeChunk::anchor)
                .first().isEqualTo("15 BR-08");
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
                        "searchKnowledge", "{\"query\":\"đổi trả hàng in theo thiết kế lỗi sản xuất\"}"))),
                        LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(10, 5, 0, 0), "scripted"),
                // 17 BR-99 was never retrieved; and 17 §4.10 does not cite 17 §4.1, should the search return it.
                new LanguageModel.Reply(new Turn.ModelTurn("{}", "Không, chỉ khi lỗi sản xuất (17 §4.10). "
                        + "Xem thêm 17 BR-99.", List.of()),
                        LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(10, 5, 0, 0), "scripted"));
        String conversation = JsonPath.read(http.asRole(Role.SALES_STAFF, seller,
                post("/api/v1/assistant/conversations")).andReturn().getResponse().getContentAsString(), "$.data.id");

        http.asRole(Role.SALES_STAFF, seller, post("/api/v1/assistant/conversations/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hàng in theo yêu cầu có được trả lại không?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("searchKnowledge"))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0]").value("17 §4.10"));
    }
}
