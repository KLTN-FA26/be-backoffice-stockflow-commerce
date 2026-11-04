package com.stockflow.assistant.internal.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arguments as the {@code jsonb} column stores them: a model without a strict mode can write arguments that are not
 * JSON, and recording them must not fail the answer they belong to.
 */
class ToolRunRepositoryAdapterTest {

    /** The character jsonb refuses, built at run time so this file holds no such byte. */
    private static final String NUL = String.valueOf((char) 0);

    /** The six characters of its escape, as a model would write it inside a JSON string. */
    private static final String NUL_ESCAPE = "\\" + "u0000";

    private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

    @Test
    @DisplayName("JSON is kept; anything else is kept as one JSON string holding it")
    void storable() {
        assertThat(ToolRunRepositoryAdapter.storable("{\"sku\":\"CUP-12OZ\"}")).isEqualTo("{\"sku\":\"CUP-12OZ\"}");
        assertThat(ToolRunRepositoryAdapter.storable("{\"sku\": CUP}")).isEqualTo("\"{\\\"sku\\\": CUP}\"");
        assertThat(ToolRunRepositoryAdapter.storable("")).isEqualTo("\"\"");
        assertThat(ToolRunRepositoryAdapter.storable(null)).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("JSON with something after it is not JSON to the column: it is kept as one string, as written")
    void trailingTokens() {
        assertThat(ToolRunRepositoryAdapter.storable("{\"sku\":\"X\"}}"))
                .isEqualTo("\"{\\\"sku\\\":\\\"X\\\"}}\"");
        assertThat(ToolRunRepositoryAdapter.storable("{\"a\":1} {\"b\":2}")).startsWith("\"");
        assertThat(ToolRunRepositoryAdapter.storable("{\"a\":1}garbage")).startsWith("\"");
    }

    @Test
    @DisplayName("a NUL, which jsonb refuses even escaped, is replaced in values, names and quoted text alike")
    void nul() {
        assertThat(ToolRunRepositoryAdapter.storable("{\"sku\":\"A" + NUL_ESCAPE + "B\"}"))
                .isEqualTo("{\"sku\":\"A" + REPLACEMENT + "B\"}");
        assertThat(ToolRunRepositoryAdapter.storable("{\"s" + NUL_ESCAPE + "ku\":1}"))
                .isEqualTo("{\"s" + REPLACEMENT + "ku\":1}");
        String quoted = ToolRunRepositoryAdapter.storable("not json " + NUL + " here");
        assertThat(quoted).doesNotContain(NUL).doesNotContain(NUL_ESCAPE).contains(REPLACEMENT);
    }
}
