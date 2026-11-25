package com.stockflow.common.config;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.stockflow.common.api.ApiResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.media.ArraySchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema names of nested DTO types (issue #459), through swagger-core's own resolver: what springdoc publishes.
 *
 * <p>Every type here is nested in this test class, so its name starts with {@code NestedSchemaNameConverterTest};
 * a top-level type of the codebase ({@link ApiResponse}) shows what keeps swagger's own name.</p>
 */
class NestedSchemaNameConverterTest {

    private static final String PREFIX = "NestedSchemaNameConverterTest";

    record Quote(List<Line> lines, Line first) {
        record Line(String sku) {
        }
    }

    record Order(List<Line> lines) {
        record Line(String sku, int cartons) {
        }
    }

    record Tree(String name, List<Tree> children) {
    }

    record Named(Inner inner) {
        @Schema(name = "ChosenName")
        record Inner(String value) {
        }
    }

    @Schema(name = "Published")
    record Renamed(Fee fee) {
        record Fee(String kind) {
        }
    }

    record WithStatus(Status status) {
        enum Status { OPEN, CLOSED }
    }

    /** {@code Pair.BC} and {@code PairB.C} join to the same name: the guard's case. */
    record Pair(BC bc) {
        record BC(String left) {
        }
    }

    record PairB(C c) {
        record C(String right) {
        }
    }

    private final NestedSchemaNameConverter converter = new NestedSchemaNameConverter();

    private Map<String, io.swagger.v3.oas.models.media.Schema> resolve(Type... types) {
        ModelConverterContextImpl context = new ModelConverterContextImpl(
                List.of(converter, new ModelResolver(Json.mapper())));
        for (Type type : types) {
            context.resolve(new AnnotatedType(type).resolveAsRef(true));
        }
        return context.getDefinedModels();
    }

    @Test
    @DisplayName("two nested records of one simple name get two schemas, each named after its enclosing type")
    void nestedRecordsOfOneNameAreTwoSchemas() {
        var schemas = resolve(Quote.class, Order.class);

        assertThat(schemas).containsKeys(PREFIX + "QuoteLine", PREFIX + "OrderLine").doesNotContainKey("Line");
        assertThat(schemas.get(PREFIX + "QuoteLine").getProperties()).containsOnlyKeys("sku");
        assertThat(schemas.get(PREFIX + "OrderLine").getProperties()).containsOnlyKeys("sku", "cartons");
        assertThat(((ArraySchema) schemas.get(PREFIX + "Order").getProperties().get("lines")).getItems().get$ref())
                .endsWith("/" + PREFIX + "OrderLine");
        assertThat(converter.collisions()).isEmpty();
    }

    @Test
    @DisplayName("an envelope over a nested type is named by the same rule; one over a top-level type keeps its name")
    void envelopes() {
        TypeFactory types = TypeFactory.defaultInstance();
        var schemas = resolve(
                types.constructParametricType(ApiResponse.class,
                        types.constructCollectionType(List.class, Quote.Line.class)),
                types.constructParametricType(ApiResponse.class,
                        types.constructCollectionType(List.class, Order.Line.class)),
                types.constructParametricType(ApiResponse.class, String.class));

        assertThat(schemas).containsKeys("ApiResponseList" + PREFIX + "QuoteLine",
                "ApiResponseList" + PREFIX + "OrderLine", "ApiResponseString");
        assertThat(converter.collisions()).isEmpty();
    }

    @Test
    @DisplayName("a self-referencing record still refers to itself, by its new name")
    void recursion() {
        var schemas = resolve(Tree.class);

        assertThat(((ArraySchema) schemas.get(PREFIX + "Tree").getProperties().get("children")).getItems().get$ref())
                .endsWith("/" + PREFIX + "Tree");
    }

    @Test
    @DisplayName("an explicit @Schema(name) wins, and a nested enum stays inline")
    void explicitNamesAndEnums() {
        var schemas = resolve(Named.class, WithStatus.class);

        assertThat(schemas).containsKey("ChosenName").doesNotContainKey(PREFIX + "NamedInner");
        assertThat(schemas).doesNotContainKeys("Status", PREFIX + "WithStatusStatus");
        io.swagger.v3.oas.models.media.Schema<?> status =
                (io.swagger.v3.oas.models.media.Schema<?>) schemas.get(PREFIX + "WithStatus").getProperties().get("status");
        assertThat(status.getEnum()).hasSize(2);
    }

    @Test
    @DisplayName("an enclosing type published under its own @Schema(name) lends that name to what it nests")
    void anEnclosingTypesPublishedName() {
        var schemas = resolve(Renamed.class);

        assertThat(schemas).containsKeys("Published", "PublishedFee");
    }

    @Test
    @DisplayName("two types the rule still gives one name (Pair.BC and PairB.C) are reported as a collision")
    void theGuardReportsWhatTheRuleCannotPrevent() {
        resolve(Pair.class, PairB.class);

        assertThat(converter.collisions()).containsOnlyKeys(PREFIX + "PairBC");
        assertThat(converter.collisions().get(PREFIX + "PairBC")).hasSize(2);
    }
}
