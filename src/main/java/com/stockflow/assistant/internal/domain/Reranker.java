package com.stockflow.assistant.internal.domain;

import java.util.List;

/**
 * Orders passages by how well each answers a query, reading the two together (a cross-encoder), where the fusion of
 * the keyword and meaning rankings only compared them apart (design §6.4). Optional: without one, the fused order is
 * the result.
 */
public interface Reranker {

    /** No reranker: the fused order stands. */
    Reranker NONE = new Reranker() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<Integer> rank(String query, List<String> texts) {
            throw new IllegalStateException("no reranker is configured");
        }
    };

    boolean available();

    /**
     * @return the indexes of {@code texts}, the one that best answers {@code query} first, each index once
     * @throws RuntimeException when the reranker could not be asked or its answer could not be read
     */
    List<Integer> rank(String query, List<String> texts);
}
