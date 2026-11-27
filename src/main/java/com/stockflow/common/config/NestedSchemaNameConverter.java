package com.stockflow.common.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names the schema of a nested DTO type after the types that enclose it: {@code LoadResponse.Shipment} is
 * {@code LoadResponseShipment}, not {@code Shipment} (issue #459). An enclosing type published under its own
 * {@code @Schema(name)} lends that name: {@code OrderResponse}, published as {@code Order}, makes {@code OrderFee}.
 *
 * <h2>Why</h2>
 *
 * <p>swagger-core names a schema by the simple class name, and this codebase nests its records: a response's
 * {@code Line}, {@code Shipment}, {@code Carton}. Every nested {@code Line} became one schema {@code Line}, the one
 * generated last overwrote the others, and Swagger documented 18 endpoints' lines with a quote request's fields -
 * silently, since the JSON on the wire follows the real class. A name per enclosing type makes the collision
 * impossible for nested types, and the rule is the same whatever was generated first, so a schema keeps its name from
 * one build to the next. Renaming each colliding record by hand ({@code LayoutBin}, {@code MapPoint}) only lasts until
 * the next DTO nests a {@code Line}.</p>
 *
 * <p>A generic envelope over a nested type is named by the same rule - {@code ApiResponse<List<X.Line>>} is
 * {@code ApiResponseListXLine} - or two envelopes over two {@code Line}s would collide in turn. Everything else keeps
 * swagger's own name: top-level classes, envelopes of top-level classes, enums (inlined, never a schema of their own)
 * and any type outside {@code com.stockflow}. An explicit {@code @Schema(name)} on the class still wins.</p>
 *
 * <h2>The guard</h2>
 *
 * <p>What the rule cannot prevent - two top-level classes of the same simple name in two modules, or {@code A.BC}
 * against {@code AB.C} - this converter notices: it records which type each schema name was given to, and a name
 * given to a second type is logged as a warning and kept in {@link #collisions()}, which an integration test asserts
 * empty. It never refuses: a broken document is better than no document.</p>
 *
 * <p>It only names, then hands the type on: where it stands in springdoc's chain of converters does not matter.</p>
 */
public class NestedSchemaNameConverter implements ModelConverter {

    private static final Logger log = LoggerFactory.getLogger(NestedSchemaNameConverter.class);
    private static final String PACKAGE = "com.stockflow.";

    private final Map<String, String> typeByName = new ConcurrentHashMap<>();
    private final Set<String> collisions = ConcurrentHashMap.newKeySet();

    @Override
    public Schema resolve(AnnotatedType annotatedType, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType type = javaType(annotatedType.getType());
        if (type != null && isOurs(type)) {
            // Not isSkipSchemaName(): swagger sets it on every property's type, and it only means "ignore the
            // property's own @Schema(name)" - the type is still named, by its simple name, unless named here.
            if (isBlank(annotatedType.getName()) && involvesNested(type)) {
                annotatedType.setName(schemaName(type));
            }
            record(type, isBlank(annotatedType.getName()) ? defaultName(type) : annotatedType.getName());
        }
        return chain.hasNext() ? chain.next().resolve(annotatedType, context, chain) : null;
    }

    /** The schema names given to more than one type, each with the types; empty while the document is right. */
    public Map<String, Set<String>> collisions() {
        Map<String, Set<String>> found = new TreeMap<>();
        for (String entry : collisions) {
            int split = entry.indexOf(' ');
            found.computeIfAbsent(entry.substring(0, split), name -> new TreeSet<>()).add(entry.substring(split + 1));
        }
        return found;
    }

    /**
     * The schema name of a type that involves a nested {@code com.stockflow} type: its enclosing type's part (see
     * {@link #classPart}) followed by its own simple name; for a generic type, its own name followed by its type
     * arguments' names, as swagger joins them ({@code ApiResponse} + {@code List} + {@code XLine}).
     */
    static String schemaName(JavaType type) {
        if (!involvesNested(type)) {
            return TypeNameResolver.std.nameForType(type);
        }
        if (type.isArrayType()) {
            return "Array" + schemaName(type.getContentType());
        }
        if (type.isCollectionLikeType()) {
            return type.getRawClass().getSimpleName() + schemaName(type.getContentType());
        }
        if (type.isMapLikeType()) {
            return "Map" + schemaName(type.getKeyType()) + schemaName(type.getContentType());
        }
        StringBuilder name = new StringBuilder(classPart(type.getRawClass()));
        for (int i = 0; i < type.containedTypeCount(); i++) {
            name.append(capitalize(schemaName(type.containedType(i))));
        }
        return name.toString();
    }

    /**
     * The class's own part of a name: its {@code @Schema(name)}, else its simple name behind its enclosing type's
     * part - so a record nested in {@code OrderResponse}, published as {@code Order}, is {@code OrderFee}, beside the
     * {@code OrderShipment} someone named by hand.
     */
    private static String classPart(Class<?> type) {
        io.swagger.v3.oas.annotations.media.Schema schema =
                type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (schema != null && !schema.name().isBlank()) {
            return schema.name();
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null ? type.getSimpleName() : classPart(enclosing) + type.getSimpleName();
    }

    /** Whether the type, or a type argument of it at any depth, is a nested {@code com.stockflow} class. */
    static boolean involvesNested(JavaType type) {
        if (type == null) {
            return false;
        }
        Class<?> raw = type.getRawClass();
        if (raw.getEnclosingClass() != null && !raw.isEnum() && raw.getName().startsWith(PACKAGE)) {
            return true;
        }
        if (type.getContentType() != null && involvesNested(type.getContentType())) {
            return true;
        }
        for (int i = 0; i < type.containedTypeCount(); i++) {
            if (involvesNested(type.containedType(i))) {
                return true;
            }
        }
        return false;
    }

    /** A {@code com.stockflow} class that becomes a schema of its own: not an enum, not a container. */
    private static boolean isOurs(JavaType type) {
        Class<?> raw = type.getRawClass();
        return raw.getName().startsWith(PACKAGE) && !raw.isEnum() && !type.isContainerType();
    }

    /** The name swagger gives the type when nothing names it: its {@code @Schema(name)}, else its own rule. */
    private static String defaultName(JavaType type) {
        io.swagger.v3.oas.annotations.media.Schema schema =
                type.getRawClass().getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (schema != null && !schema.name().isBlank()) {
            return schema.name();
        }
        return TypeNameResolver.std.nameForType(type);
    }

    private void record(JavaType type, String name) {
        String canonical = type.toCanonical();
        String first = typeByName.putIfAbsent(name, canonical);
        if (first != null && !first.equals(canonical)
                && collisions.addAll(List.of(name + " " + first, name + " " + canonical))) {
            log.warn("OpenAPI schema name {} is given to both {} and {}: Swagger documents one of them with the "
                    + "other's fields", name, first, canonical);
        }
    }

    private static JavaType javaType(Type type) {
        if (type instanceof JavaType javaType) {
            return javaType;
        }
        return type == null ? null : TypeFactory.defaultInstance().constructType(type);
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
