package com.stockflow.assistant.internal.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text a vector travels to PostgreSQL as, cast there to {@code real[]}. The round trip through the database is
 * {@code KnowledgeEmbeddingIntegrationTest}'s.
 */
class KnowledgeRepositoryAdapterTest {

    @Test
    @DisplayName("a vector is written as an array literal of Float.toString values")
    void literal() {
        assertThat(KnowledgeRepositoryAdapter.literal(new float[] {0.25f, -1.5f, 0.1f, 0f}))
                .isEqualTo("{0.25,-1.5,0.1,0.0}");
        assertThat(KnowledgeRepositoryAdapter.literal(new float[0])).isEqualTo("{}");
    }

    @Test
    @DisplayName("every value reads back as the same float, the smallest and the longest included")
    void literalRoundTrips() {
        float[] vector = {6.103515625E-5f, Float.MIN_NORMAL, Float.MIN_VALUE, 123456.79f, -0.031415927f};

        String literal = KnowledgeRepositoryAdapter.literal(vector);

        assertThat(literal).startsWith("{").endsWith("}").doesNotContain(" ");
        String[] values = literal.substring(1, literal.length() - 1).split(",");
        assertThat(values).hasSameSizeAs(vector);
        for (int i = 0; i < vector.length; i++) {
            assertThat(Float.parseFloat(values[i])).as(values[i]).isEqualTo(vector[i]);
        }
    }
}
