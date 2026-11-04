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

    private static final KnowledgeChunk FLOW = new KnowledgeChunk("04c-all-flows", "F-PO", KnowledgeChunk.Kind.FLOW,
            "F-PO — Đơn mua hàng gửi nhà cung cấp (Nhập hàng)", "Từ nháp tới lúc nhà cung cấp xác nhận.");

    private final InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();

    @SuppressWarnings("unchecked")
    private SearchKnowledgeTool tool(boolean multiQuery) {
        FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
        embeddings.available = false;
        KnowledgeService knowledge = new KnowledgeService(repository, embeddings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), mock(ObjectProvider.class),
                Clock.systemUTC());
        return new SearchKnowledgeTool(knowledge, new KnowledgeProperties(3.0, 10, multiQuery,
                new KnowledgeProperties.Reranker("", Duration.ofSeconds(5), 20), multiQuery));
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
    @DisplayName("the flows are named to the model only when they are indexed; what foundBy means always is")
    void flowsOnlyWhenIndexed() {
        assertThat(SearchKnowledgeTool.description(true)).contains("F-PO", "the flows in Vietnamese",
                "which statuses exist is for the state machines");
        assertThat(SearchKnowledgeTool.description(false)).doesNotContain("F-PO", "flow").contains("foundBy",
                "found by meaning alone");
    }

    @Test
    @DisplayName("a question longer than the search reads is cut, and the search goes ahead")
    void aLongQuestionIsCut() {
        repository.sync("04c-all-flows", List.of(FLOW), null);
        repository.ranks("quy trình mua hàng", FLOW);

        SearchKnowledgeTool.Answer answer = tool(true).run(ToolArguments.parse(new ObjectMapper(),
                "{\"query\":\"quy trình mua hàng\",\"question\":\"" + "x".repeat(4000) + "\"}"));

        assertThat(answer.passages()).hasSize(1);
    }

    @Test
    @DisplayName("each passage says how the search found it")
    void foundBy() {
        repository.sync("04c-all-flows", List.of(FLOW), null);
        repository.ranks("quy trình mua hàng", FLOW);

        SearchKnowledgeTool.Answer answer = tool(true).run(ToolArguments.parse(new ObjectMapper(),
                "{\"query\":\"quy trình mua hàng\",\"question\":\"Mua hàng đi qua những bước nào?\"}"));

        assertThat(answer.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.anchor()).isEqualTo("F-PO");
            assertThat(passage.foundBy()).isEqualTo("words");
        });
    }
}
