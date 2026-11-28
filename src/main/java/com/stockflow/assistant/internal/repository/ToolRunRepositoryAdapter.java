package com.stockflow.assistant.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolRunRepository;
import com.stockflow.assistant.internal.entity.AssistantToolCallJpaEntity;
import com.stockflow.common.id.Identifiers;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link ToolRunRepository} on {@code assistant.tool_call}, the table chat runs are stored in too. */
@Repository
class ToolRunRepositoryAdapter implements ToolRunRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One value and nothing after it: {@code readTree} alone ignores what follows the first value. */
    private static final ObjectReader STRICT = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AssistantToolCallJpaRepository toolCalls;

    ToolRunRepositoryAdapter(AssistantToolCallJpaRepository toolCalls) {
        this.toolCalls = toolCalls;
    }

    @Override
    public void saveStandalone(ToolChannel channel, UUID userId, List<ToolExecution> executions) {
        if (channel == ToolChannel.CHAT) {
            throw new IllegalArgumentException("a chat run is stored with its turn, not alone");
        }
        for (ToolExecution execution : executions) {
            toolCalls.save(new AssistantToolCallJpaEntity(Identifiers.newId(), channel, userId, null, null,
                    execution.call().id(), execution.call().name(), storable(execution.call().arguments()),
                    execution.outcome(), execution.result(),
                    (int) Math.min(Integer.MAX_VALUE, execution.durationMs())));
        }
    }

    @Override
    public int deleteStandaloneBefore(Instant cutoff, int limit) {
        return toolCalls.deleteStandaloneBefore(cutoff, limit);
    }

    /**
     * {@code arguments} as the {@code jsonb} column takes them, so recording a call never fails the answer it belongs
     * to. A model without a strict mode can write arguments that are not JSON, or JSON with something after it
     * ({@code {"sku":"X"}}}); the tool refused them or read the first value, and the record keeps what was written.
     * <ul>
     *   <li>One JSON value and nothing after it: kept, written back canonically.</li>
     *   <li>Anything else: one JSON string holding the text as written.</li>
     *   <li>A NUL character, which {@code jsonb} refuses even escaped, becomes U+FFFD in either case.</li>
     * </ul>
     */
    static String storable(String arguments) {
        String text = arguments == null ? "" : arguments;
        try {
            JsonNode parsed = STRICT.readTree(text);
            if (parsed != null && !parsed.isMissingNode()) {
                return JSON.writeValueAsString(withoutNul(parsed));
            }
        } catch (JsonProcessingException notJson) {
            // Kept below, quoted.
        }
        try {
            return JSON.writeValueAsString(text.replace('\u0000', '\uFFFD'));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a string always serialises", impossible);
        }
    }

    /** {@code node} with every NUL in its names and texts replaced; {@code jsonb} stores no NUL. */
    private static JsonNode withoutNul(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            return text.indexOf('\u0000') < 0 ? node : TextNode.valueOf(text.replace('\u0000', '\uFFFD'));
        }
        if (node.isArray()) {
            ArrayNode clean = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> clean.add(withoutNul(element)));
            return clean;
        }
        if (node.isObject()) {
            ObjectNode clean = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> clean.set(entry.getKey().replace('\u0000', '\uFFFD'),
                    withoutNul(entry.getValue())));
            return clean;
        }
        return node;
    }
}
