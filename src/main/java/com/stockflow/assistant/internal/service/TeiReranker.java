package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.assistant.internal.domain.Reranker;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Reranker} over HuggingFace text-embeddings-inference ({@code POST /rerank}): the query and the passages in,
 * a score per passage out. One model per server, so nothing names it here.
 *
 * <p>The answer is checked, not trusted: an index outside the passages, or one given twice, is an answer this class
 * cannot read, and the search keeps its fused order. A passage the server left out keeps its fused place after the
 * ones it scored.</p>
 */
final class TeiReranker implements Reranker {

    private final RestClient http;

    TeiReranker(RestClient http) {
        this.http = http;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<Integer> rank(String query, List<String> texts) {
        JsonNode answer = http.post().uri("/rerank").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "texts", texts, "truncate", true))
                .retrieve().body(JsonNode.class);
        if (answer == null || !answer.isArray()) {
            throw new IllegalStateException("the reranker's answer is not a list of scores");
        }
        record Scored(int index, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode entry : answer) {
            JsonNode index = entry.path("index");
            JsonNode score = entry.path("score");
            if (!index.canConvertToInt() || !score.isNumber()) {
                throw new IllegalStateException("a score of the reranker's answer has no index or no value");
            }
            int at = index.asInt();
            if (at < 0 || at >= texts.size() || !seen.add(at)) {
                throw new IllegalStateException("the reranker scored a passage it was not sent, or one twice");
            }
            scored.add(new Scored(at, score.asDouble()));
        }
        List<Integer> order = new ArrayList<>(scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index))
                .map(Scored::index).toList());
        for (int i = 0; i < texts.size(); i++) {
            if (!seen.contains(i)) {
                order.add(i);
            }
        }
        return order;
    }
}
