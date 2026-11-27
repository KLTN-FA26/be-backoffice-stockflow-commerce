package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchKnowledgeToolTest {

    private static final KnowledgeChunk FLOW = new KnowledgeChunk("07-picking", "07 §4.4", KnowledgeChunk.Kind.FLOW,
            "07 §4.4 Thiếu hàng (Short pick) — Lấy hàng (Picking)", "9. **Báo thiếu** — picker báo thiếu kèm vị trí.");

    private final InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();

    @SuppressWarnings("unchecked")
    private SearchKnowledgeTool tool(boolean multiQuery) {
        FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
        embeddings.available = false;
        KnowledgeService knowledge = new KnowledgeService(repository, embeddings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), mock(ObjectProvider.class),
                Clock.systemUTC());
        return new SearchKnowledgeTool(knowledge, new KnowledgeProperties(3.0, 10, multiQuery,
                new KnowledgeProperties.Reranker("", Duration.ofSeconds(5), 20), multiQuery, 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(SearchKnowledgeTool tool) {
        return (Map<String, Object>) tool.spec().inputSchema().get("properties");
    }

    @Test
    @DisplayName("the question is asked for only when it is searched: a model is never made to copy it for nothing")
    void theQuestionOnlyWhenSearched() {
        assertThat(properties(tool(false))).containsOnlyKeys("query");
        assertThat(properties(tool(true))).containsOnlyKeys("query", "question");
    }

    @Test
    @DisplayName("the steps of a process are named to the model only when they are indexed; what foundBy means, and "
            + "that an anchor carries its chapter, always are")
    void flowsOnlyWhenIndexed() {
        assertThat(SearchKnowledgeTool.description(true)).contains("the steps of each process", "§4 the steps");
        assertThat(SearchKnowledgeTool.description(false)).doesNotContain("steps").contains("foundBy",
                "found by meaning alone", "'15 BR-08' is rule 8 of chapter 15", "Vietnamese keywords");
    }

    @Test
    @DisplayName("a question longer than the search reads is cut, and the search goes ahead")
    void aLongQuestionIsCut() {
        repository.sync("07-picking", List.of(FLOW), null);
        repository.ranks("quy trình báo thiếu", FLOW);

        SearchKnowledgeTool.Answer answer = tool(true).run(ToolArguments.parse(new ObjectMapper(),
                "{\"query\":\"quy trình báo thiếu\",\"question\":\"" + "x".repeat(4000) + "\"}"));

        assertThat(answer.passages()).hasSize(1);
    }

    @Test
    @DisplayName("each passage says how the search found it")
    void foundBy() {
        repository.sync("07-picking", List.of(FLOW), null);
        repository.ranks("quy trình báo thiếu", FLOW);

        SearchKnowledgeTool.Answer answer = tool(true).run(ToolArguments.parse(new ObjectMapper(),
                "{\"query\":\"quy trình báo thiếu\",\"question\":\"Nhặt thiếu thì làm gì?\"}"));

        assertThat(answer.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.anchor()).isEqualTo("07 §4.4");
            assertThat(passage.foundBy()).isEqualTo("words");
        });
    }
}
