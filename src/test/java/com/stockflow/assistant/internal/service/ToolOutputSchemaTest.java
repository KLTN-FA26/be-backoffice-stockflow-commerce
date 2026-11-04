package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every tool's output schema against what the tool-result mapper actually writes ({@link ToolOutputSchema}): a client
 * that validates {@code structuredContent} against a wrong schema fails a call that worked, so for each tool an answer
 * is built with every field set, and one with every field that may be null left null, written by the mapper and held
 * to the schema. The schema must also name every field written, so none is missed, and describe every value, so none
 * is the {@code {}} that accepts anything.
 */
class ToolOutputSchemaTest {

    /** The application's mapper as {@code spring.jackson.*} sets it up, then as AssistantTools copies it. */
    private static final ObjectMapper RESULTS = AssistantTools.resultMapper(Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build());

    static Stream<Class<?>> toolClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantTool.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.stockflow.assistant.internal.service")) {
            try {
                Class<?> tool = Class.forName(candidate.getBeanClassName());
                // The application's tools, not the fakes other tests of this package declare.
                if (tool.isAnnotationPresent(org.springframework.stereotype.Component.class)) {
                    classes.add(tool);
                }
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException(missing);
            }
        }
        return classes.stream();
    }

    @Test
    @DisplayName("every tool is found, so none is left out of the cases below")
    void everyToolIsChecked() {
        assertThat(toolClasses().count()).isGreaterThanOrEqualTo(19);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toolClasses")
    @DisplayName("an answer with every field, and one with the optional fields absent, are valid against the tool's "
            + "schema, which names every field and describes every value")
    void theSchemaDescribesWhatIsWritten(Class<?> tool) {
        Map<String, Object> schema = ToolOutputSchema.forToolClass(RESULTS, tool)
                .orElseThrow(() -> new AssertionError(tool.getSimpleName() + " has no output schema"));
        assertThat(schema.get("type")).isEqualTo("object");
        everyValueDescribed(schema, tool.getSimpleName());

        Type answer = answerType(tool);
        JsonNode full = RESULTS.valueToTree(sample(answer, false, 0));
        JsonNode sparse = RESULTS.valueToTree(sample(answer, true, 0));
        valid(full, schema, tool.getSimpleName(), true);
        valid(sparse, schema, tool.getSimpleName(), true);
    }

    @Test
    @DisplayName("an Instant is text with its offset and a LocalDate an ISO date, as the mapper writes them; the "
            + "schema says so in words, never with a format a validator might not know")
    void datesAreDescribedNotFormatted() {
        record Dated(Instant at, LocalDate on, long count, boolean done) {
        }
        Map<String, Object> schema = new ToolOutputSchemaProbe().schemaOf(Dated.class);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(properties.get("at")).containsEntry("type", "string").doesNotContainKey("format");
        assertThat(properties.get("on")).containsEntry("type", "string").doesNotContainKey("format");
        assertThat(properties.get("count")).containsEntry("type", "integer");
        assertThat(schema.get("required")).isEqualTo(List.of("count", "done"));
        JsonNode written = RESULTS.valueToTree(new Dated(Instant.parse("2026-10-27T17:30:12Z"), LocalDate.of(2026, 10,
                28), 2, true));
        assertThat(written.path("at").asText()).isEqualTo("2026-10-28T00:30:12+07:00");
        assertThat(OffsetDateTime.parse(written.path("at").asText())).isNotNull();
        assertThat(written.path("on").asText()).isEqualTo("2026-10-28");
    }

    @Test
    @DisplayName("a tool that declares only Object has no schema, and is listed without one")
    void noSchemaForObject() {
        assertThat(ToolOutputSchema.forToolClass(RESULTS, AssistantTool.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Reaches the generator for one record, through a tool class whose run returns it. */
    private static final class ToolOutputSchemaProbe {

        Map<String, Object> schemaOf(Class<?> type) {
            return ToolOutputSchema.forType(RESULTS, RESULTS.getTypeFactory().constructType(type));
        }
    }

    private static Type answerType(Class<?> tool) {
        for (Method method : tool.getMethods()) {
            if (method.getName().equals("run") && !method.isBridge() && method.getParameterCount() == 1) {
                return method.getGenericReturnType();
            }
        }
        throw new AssertionError(tool.getSimpleName() + " has no run(ToolArguments)");
    }

    /** A value of {@code type}: every field set, or with {@code sparse} every field that may be null left null. */
    private static Object sample(Type type, boolean sparse, int depth) {
        if (depth > 8) {
            throw new AssertionError("an answer nested deeper than 8: a type that contains itself?");
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw) || Collection.class == raw) {
                return List.of(sample(arguments[0], sparse, depth + 1));
            }
            if (Set.class.isAssignableFrom(raw)) {
                return Set.of(sample(arguments[0], sparse, depth + 1));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return Map.of("key", sample(arguments[1], sparse, depth + 1));
            }
            if (Optional.class == raw) {
                return Optional.of(sample(arguments[0], sparse, depth + 1));
            }
            fail("an answer field of a generic type the test does not build: " + type);
        }
        Class<?> raw = (Class<?>) type;
        if (raw == String.class) {
            return "SO-20261028-000123";
        }
        if (raw == int.class || raw == Integer.class) {
            return 3;
        }
        if (raw == long.class || raw == Long.class) {
            return 7L;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return true;
        }
        if (raw == double.class || raw == Double.class) {
            return 1.5;
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal("1250000.50");
        }
        if (raw == Instant.class) {
            return Instant.parse("2026-10-27T17:30:12.123456Z");
        }
        if (raw == LocalDate.class) {
            return LocalDate.of(2026, 10, 28);
        }
        if (raw == UUID.class) {
            return UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = sparse && !components[i].getType().isPrimitive() ? null
                        : sample(components[i].getGenericType(), sparse, depth + 1);
            }
            try {
                var constructor = raw.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (ReflectiveOperationException | RuntimeException refused) {
                // A record that refuses nulls in its constructor: build that one full.
                if (sparse) {
                    return sample(type, false, depth);
                }
                throw new AssertionError("cannot build " + raw.getName() + ": " + refused, refused);
            }
        }
        return fail("an answer field of a type the test does not build: " + raw.getName());
    }

    /** Asserts that {@code node} is valid against {@code schema}, and with {@code closed} that it has no other field. */
    @SuppressWarnings("unchecked")
    private static void valid(JsonNode node, Map<String, Object> schema, String path, boolean closed) {
        Object type = schema.get("type");
        if (type == null) {
            return;
        }
        switch ((String) type) {
            case "object" -> {
                assertThat(node.isObject()).as("%s is an object: %s", path, node).isTrue();
                Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                for (Object required : (List<Object>) schema.getOrDefault("required", List.of())) {
                    assertThat(node.has((String) required)).as("%s has %s", path, required).isTrue();
                }
                Object additional = schema.get("additionalProperties");
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Map<String, Object> property = (Map<String, Object>) properties.get(field.getKey());
                    if (property == null && additional instanceof Map<?, ?> values) {
                        valid(field.getValue(), (Map<String, Object>) values, path + "." + field.getKey(), closed);
                    } else if (property == null) {
                        assertThat(closed).as("%s.%s is written but not in the schema", path, field.getKey())
                                .isFalse();
                    } else {
                        valid(field.getValue(), property, path + "." + field.getKey(), closed);
                    }
                }
            }
            case "array" -> {
                assertThat(node.isArray()).as("%s is an array: %s", path, node).isTrue();
                Map<String, Object> items = (Map<String, Object>) schema.get("items");
                if (items != null) {
                    node.forEach(item -> valid(item, items, path + "[]", closed));
                }
            }
            case "string" -> {
                assertThat(node.isTextual()).as("%s is text: %s", path, node).isTrue();
                List<Object> values = (List<Object>) schema.get("enum");
                if (values != null) {
                    assertThat(values).as("%s is one of its values", path).contains(node.textValue());
                }
            }
            case "integer" -> assertThat(node.isIntegralNumber()).as("%s is an integer: %s", path, node).isTrue();
            case "number" -> assertThat(node.isNumber()).as("%s is a number: %s", path, node).isTrue();
            case "boolean" -> assertThat(node.isBoolean()).as("%s is a boolean: %s", path, node).isTrue();
            default -> fail(path + " has a type the schema should not use: " + type);
        }
    }

    /** Every schema in the tree states a type: none is the {@code {}} that accepts anything. */
    @SuppressWarnings("unchecked")
    private static void everyValueDescribed(Map<String, Object> schema, String path) {
        assertThat(schema).as("%s states a type", path).containsKey("type");
        assertThat(schema).as("%s uses no format keyword", path).doesNotContainKey("format");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            properties.forEach((name, property) -> everyValueDescribed((Map<String, Object>) property,
                    path + "." + name));
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            everyValueDescribed((Map<String, Object>) items, path + "[]");
        }
        if (schema.get("additionalProperties") instanceof Map<?, ?> values) {
            everyValueDescribed((Map<String, Object>) values, path + "{}");
        }
    }
}
