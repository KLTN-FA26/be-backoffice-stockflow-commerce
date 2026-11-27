package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The routing settings as the application binds them (design §11): from {@code application.yml}, whose placeholders
 * resolve to blanks when the environment sets nothing, and from {@code ASSISTANT_MODELS}, a comma list.
 */
class ModelRoutingPropertiesTest {

    private static final String PREFIX = "stockflow.assistant.routing";

    private static ModelRoutingProperties bound(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(PREFIX,
                ModelRoutingProperties.class);
    }

    @Test
    @DisplayName("unset, routing changes nothing: the single model, escalation on, a spent quota set aside until the "
            + "provider's quota day ends")
    void unset() {
        ModelRoutingProperties blanks = bound(Map.of(PREFIX + ".models", "", PREFIX + ".escalate-on-failure", "true",
                PREFIX + ".quota-cooldown", ""));
        ModelRoutingProperties absent = bound(Map.of());

        for (ModelRoutingProperties routing : List.of(blanks, absent)) {
            assertThat(routing.models()).isEmpty();
            assertThat(routing.route("claude-opus-5-5")).containsExactly("claude-opus-5-5");
            assertThat(routing.escalateOnFailure()).isTrue();
            assertThat(routing.quotaCooldown()).isNull();
        }
    }

    @Test
    @DisplayName("ASSISTANT_MODELS is read as typed: spaces, empty entries and repeats are not models; the order is "
            + "kept")
    void aCommaList() {
        ModelRoutingProperties routing = bound(Map.of(PREFIX + ".models",
                " gemini-3.5-flash-lite, gemini-3.5-flash,,gemini-3.5-flash-lite ", PREFIX + ".escalate-on-failure",
                "false", PREFIX + ".quota-cooldown", "PT6H"));

        assertThat(routing.route("claude-opus-5-5")).containsExactly("gemini-3.5-flash-lite", "gemini-3.5-flash");
        assertThat(routing.escalateOnFailure()).isFalse();
        assertThat(routing.quotaCooldown()).isEqualTo(Duration.ofHours(6));

        assertThat(new ModelRoutingProperties(Arrays.asList("a", null, " ", "b"), true, null).models())
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("a cooldown that is zero or negative would set nothing aside: refused")
    void cooldownMustBePositive() {
        assertThatThrownBy(() -> new ModelRoutingProperties(List.of(), true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quota-cooldown");
        assertThatThrownBy(() -> new ModelRoutingProperties(List.of(), true, Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
