package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The model's arguments are untrusted input: strict tool use fixes their shape, not their values. */
class ToolArgumentsTest {

    private static ToolArguments args(String json) {
        return ToolArguments.parse(new ObjectMapper(), json);
    }

    @Test
    @DisplayName("strings are trimmed; blank is absent; too long is refused")
    void text() {
        ToolArguments arguments = args("{\"sku\":\"  CUP-1 \",\"search\":\"  \",\"long\":\"abcdef\"}");
        assertThat(arguments.text("sku", 64)).isEqualTo("CUP-1");
        assertThat(arguments.optionalText("search", 10)).isNull();
        assertThatThrownBy(() -> arguments.text("search", 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> arguments.text("long", 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> args("{\"sku\":5}").text("sku", 64)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("integers must be whole numbers inside their range")
    void integers() {
        assertThat(args("{\"limit\":50}").integer("limit", 1, 50)).isEqualTo(50);
        for (String bad : List.of("{\"limit\":0}", "{\"limit\":51}", "{\"limit\":2.5}", "{\"limit\":\"9\"}", "{}")) {
            assertThatThrownBy(() -> args(bad).integer("limit", 1, 50)).as(bad).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("a choice must be one of its values exactly")
    void choice() {
        assertThat(args("{\"status\":\"DRAFT\"}").choice("status", List.of("ANY", "DRAFT"))).isEqualTo("DRAFT");
        assertThatThrownBy(() -> args("{\"status\":\"draft\"}").choice("status", List.of("ANY", "DRAFT")))
                .isInstanceOf(BusinessException.class);
    }
}
