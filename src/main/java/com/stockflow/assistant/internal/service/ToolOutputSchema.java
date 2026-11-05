package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The JSON Schema of what a tool answers, for MCP's {@code outputSchema} (design §7): read off the record its
 * {@code run} returns, the way the tool-result mapper writes it.
 *
 * <p><b>Generated, never written by hand.</b> A client that is given an output schema validates the tool's
 * {@code structuredContent} against it and fails the call when they disagree, so a wrong schema breaks a tool that
 * works, which is worse than giving none. A schema typed per tool would drift the first time a field is added to its
 * record. This reads the record through the same Jackson introspection that serialises it, and asks the mapper itself
 * how it writes each JDK value ({@link #VALUE_SAMPLES}): a field is in the schema exactly when it is in the JSON,
 * under the name and in the shape it is written with.</p>
 *
 * <p><b>What it states, and what it leaves open.</b></p>
 * <ul>
 *   <li>The type of every property, the items of every array and nested objects inline. There is no {@code $ref}:
 *       these records are not recursive, and inline schemas mean nothing for a client's validator to resolve.</li>
 *   <li>Only primitive-typed properties are {@code required}: the mapper leaves nulls out
 *       ({@code default-property-inclusion: non_null}), so any other field may be absent.</li>
 *   <li>Objects stay open (no {@code additionalProperties: false}): a client holding the schema of an earlier list
 *       then accepts a result with a field added since.</li>
 *   <li>A type it cannot describe (an {@code Object}, a {@code JsonNode}, a type that contains itself) is {@code {}},
 *       which accepts anything: never a guess. {@code ToolOutputSchemaTest} holds every tool to having none.</li>
 * </ul>
 *
 * <p>No {@code format} keyword: a validator that does not know a format refuses the whole schema, so a date says
 * what it is in a {@code description}. No {@code $schema} either: MCP reads a schema without one as JSON Schema
 * 2020-12, and nothing here differs between that and draft-07.</p>
 *
 * <p>A tool whose {@code run} declares only {@code Object} (a test's mock of the interface) has no schema, and is
 * listed without one.</p>
 */
final class ToolOutputSchema {

    /**
     * One value of each JDK type a tool's record may hold that is not a plain number, text or boolean: the mapper
     * writing it says how it comes out ({@code Instant} as text through the tool-result serialiser, a {@code Duration}
     * as a number unless configured otherwise), so the schema follows the mapper's configuration instead of assuming
     * one.
     */
    private static final Map<Class<?>, Object> VALUE_SAMPLES = Map.ofEntries(
            Map.entry(Instant.class, Instant.parse("2026-01-02T03:04:05Z")),
            Map.entry(OffsetDateTime.class, OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(7))),
            Map.entry(ZonedDateTime.class, ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(7))),
            Map.entry(LocalDateTime.class, LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
            Map.entry(LocalDate.class, LocalDate.of(2026, 1, 2)),
            Map.entry(LocalTime.class, LocalTime.of(3, 4, 5)),
            Map.entry(YearMonth.class, YearMonth.of(2026, 1)),
            Map.entry(Year.class, Year.of(2026)),
            Map.entry(Duration.class, Duration.ofMinutes(90)),
            Map.entry(Period.class, Period.ofDays(3)),
            Map.entry(UUID.class, UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b")));

    private static final Set<Class<?>> DATE_TIMES = Set.of(Instant.class, OffsetDateTime.class, ZonedDateTime.class);

    private ToolOutputSchema() {
    }

    /** The schema of what {@code tool} answers; empty when its {@code run} declares nothing more than Object. */
    static Optional<Map<String, Object>> of(ObjectMapper mapper, AssistantTool tool) {
        return forToolClass(mapper, AopUtils.getTargetClass(tool));
    }

    /** As {@link #of}, from the tool's class: what a test reads without building the tool. */
    static Optional<Map<String, Object>> forToolClass(ObjectMapper mapper, Class<?> toolClass) {
        Type answer = answerType(toolClass);
        if (answer == null || answer == Object.class) {
            return Optional.empty();
        }
        Map<String, Object> schema = forType(mapper, mapper.getTypeFactory().constructType(answer));
        return "object".equals(schema.get("type")) ? Optional.of(schema) : Optional.empty();
    }

    /** The schema of one type as {@code mapper} writes it; {@code {}} for one it cannot describe. */
    static Map<String, Object> forType(ObjectMapper mapper, JavaType type) {
        return new Builder(mapper).schema(type);
    }

    /** The declared return type of {@code run(ToolArguments)}: the covariant one, never the bridge to Object. */
    private static Type answerType(Class<?> toolClass) {
        Type found = null;
        for (Method method : toolClass.getMethods()) {
            if (method.getName().equals("run") && !method.isBridge() && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ToolArguments.class) {
                if (found == null || found == Object.class) {
                    found = method.getGenericReturnType();
                }
            }
        }
        return found;
    }

    private static final class Builder {

        private final ObjectMapper mapper;
        private final SerializationConfig config;
        private final Set<JavaType> enclosing = new HashSet<>();

        Builder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.config = mapper.getSerializationConfig();
        }

        Map<String, Object> schema(JavaType type) {
            Class<?> raw = type.getRawClass();
            if (raw == boolean.class || raw == Boolean.class) {
                return typed("boolean");
            }
            if (raw == byte.class || raw == short.class || raw == int.class || raw == long.class
                    || raw == Byte.class || raw == Short.class || raw == Integer.class || raw == Long.class
                    || raw == BigInteger.class) {
                return typed("integer");
            }
            if (raw == float.class || raw == double.class || raw == Float.class || raw == Double.class
                    || raw == BigDecimal.class) {
                return typed("number");
            }
            // A customer's name is written as text: the name, or its alias for the assistant's own model (ADR-0014).
            if (CharSequence.class.isAssignableFrom(raw) || raw == char.class || raw == Character.class
                    || raw == byte[].class || raw == CustomerName.class) {
                return typed("string");
            }
            if (raw.isEnum()) {
                return enumeration(raw);
            }
            if (VALUE_SAMPLES.containsKey(raw)) {
                return sampled(raw);
            }
            if (type.isArrayType() || type.isCollectionLikeType()) {
                Map<String, Object> schema = typed("array");
                schema.put("items", schema(type.getContentType()));
                return schema;
            }
            if (type.isMapLikeType()) {
                Map<String, Object> schema = typed("object");
                schema.put("additionalProperties", schema(type.getContentType()));
                return schema;
            }
            if (raw == Optional.class && type.containedTypeCount() == 1) {
                return schema(type.containedType(0));
            }
            if (raw == Object.class || JsonNode.class.isAssignableFrom(raw) || raw.isPrimitive()
                    || raw.getName().startsWith("java.")) {
                return new LinkedHashMap<>();
            }
            return object(type);
        }

        private Map<String, Object> object(JavaType type) {
            if (!enclosing.add(type)) {
                return new LinkedHashMap<>();
            }
            try {
                BeanDescription description = config.introspect(type);
                Map<String, Object> properties = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();
                for (BeanPropertyDefinition property : description.findProperties()) {
                    if (!property.couldSerialize() || property.getPrimaryType() == null) {
                        continue;
                    }
                    properties.put(property.getName(), schema(property.getPrimaryType()));
                    if (property.getPrimaryType().isPrimitive()) {
                        required.add(property.getName());
                    }
                }
                if (properties.isEmpty()) {
                    return new LinkedHashMap<>();
                }
                Map<String, Object> schema = typed("object");
                schema.put("properties", Collections.unmodifiableMap(properties));
                if (!required.isEmpty()) {
                    schema.put("required", List.copyOf(required));
                }
                return schema;
            } finally {
                enclosing.remove(type);
            }
        }

        /** The constants as the mapper writes them, which is their name unless the enum or the mapper says otherwise. */
        private Map<String, Object> enumeration(Class<?> raw) {
            List<String> values = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                JsonNode written = mapper.valueToTree(constant);
                if (!written.isTextual()) {
                    return new LinkedHashMap<>();
                }
                values.add(written.textValue());
            }
            Map<String, Object> schema = typed("string");
            schema.put("enum", List.copyOf(values));
            return schema;
        }

        private Map<String, Object> sampled(Class<?> raw) {
            JsonNode written = mapper.valueToTree(VALUE_SAMPLES.get(raw));
            Map<String, Object> schema;
            if (written.isTextual()) {
                schema = typed("string");
                // A description, never "format": a validator that does not know a format refuses the whole schema
                // (Ajv without its formats plugin does), and the model reading the schema needs only the words.
                if (DATE_TIMES.contains(raw) && parsesAsDateTime(written.textValue())) {
                    schema.put("description", "An ISO 8601 date and time with its UTC offset");
                } else if (raw == LocalDate.class && parsesAsDate(written.textValue())) {
                    schema.put("description", "An ISO 8601 date, YYYY-MM-DD");
                }
            } else if (written.isIntegralNumber()) {
                schema = typed("integer");
            } else if (written.isNumber()) {
                schema = typed("number");
            } else if (written.isArray()) {
                schema = typed("array");
            } else {
                schema = new LinkedHashMap<>();
            }
            return schema;
        }

        private static boolean parsesAsDateTime(String text) {
            try {
                OffsetDateTime.parse(text);
                return true;
            } catch (RuntimeException notOne) {
                return false;
            }
        }

        private static boolean parsesAsDate(String text) {
            try {
                LocalDate.parse(text);
                return true;
            } catch (RuntimeException notOne) {
                return false;
            }
        }

        private static Map<String, Object> typed(String type) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type);
            return schema;
        }
    }
}
