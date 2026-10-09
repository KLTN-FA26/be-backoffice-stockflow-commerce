package com.stockflow.warehouse.internal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document of the {@code warehouse} group, as springdoc generates it from the running
 * application: what the frontend reads in Swagger UI.
 *
 * <p>MockMvc over the context every integration test already shares, rather than
 * {@code @AutoConfigureMockMvc}, which would change the cache key and start a second context.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class WarehouseOpenApiIntegrationTest {

    private static final List<String> TAGS = List.of("Warehouses", "Warehouse map: zones",
            "Warehouse map: shelves", "Warehouse map: areas", "Warehouse map: boundaries",
            "Warehouse map: layout and lookup");

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    private JsonNode document;

    @BeforeEach
    void readTheDocument() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        document = objectMapper.readTree(mvc.perform(get("/v3/api-docs/warehouse"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<Map.Entry<String, JsonNode>> operations() {
        List<Map.Entry<String, JsonNode>> operations = new ArrayList<>();
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(
                operation -> operations.add(Map.entry(operation.getKey().toUpperCase() + " " + path.getKey(),
                        operation.getValue()))));
        return operations;
    }

    private JsonNode schema(String name) {
        return document.path("components").path("schemas").path(name);
    }

    /** The schema behind {@code data} of the operation's success response. */
    private JsonNode dataOf(String path, String method) {
        JsonNode content = document.path("paths").path(path).path(method).path("responses").elements().next()
                .path("content").elements().next();
        String envelope = content.path("schema").path("$ref").asText().replace("#/components/schemas/", "");
        return schema(envelope).path("properties").path("data");
    }

    @Test
    @DisplayName("all 27 endpoints of the map are documented, each with a summary and one of the six tags")
    void everyEndpointIsDocumented() {
        assertThat(operations()).hasSize(27).allSatisfy(operation -> {
            assertThat(operation.getValue().path("summary").asText()).as(operation.getKey()).isNotBlank();
            assertThat(operation.getValue().path("tags").get(0).asText()).as(operation.getKey()).isIn(TAGS);
        });
    }

    @Test
    @DisplayName("each endpoint's data has its own schema: a shelf is a Shelf, the layout a WarehouseLayout")
    void responsesAreNotCollapsed() {
        assertThat(dataOf("/api/v1/shelves/{shelfId}", "get").path("$ref").asText()).endsWith("/Shelf");
        assertThat(dataOf("/api/v1/warehouses/{warehouseId}/layout", "get").path("$ref").asText())
                .endsWith("/WarehouseLayout");
        assertThat(dataOf("/api/v1/locations/{locationCode}", "get").path("$ref").asText())
                .endsWith("/StorageLocation");
        assertThat(dataOf("/api/v1/shelves/{shelfId}/bin-generation", "post").path("items").path("$ref").asText())
                .endsWith("/GeneratedBin");
    }

    @Test
    @DisplayName("the layout's bin and the shelf's bin are two schemas: only the layout's carries effectiveStatus")
    void nestedRecordsKeepTheirOwnSchema() {
        assertThat(schema("LayoutBin").path("properties").has("effectiveStatus")).isTrue();
        assertThat(schema("LayoutBin").path("properties").has("usable")).isTrue();
        assertThat(schema("ShelfBin").path("properties").has("effectiveStatus")).isFalse();
    }

    @Test
    @DisplayName("deleting a boundary documents version as a required query parameter")
    void deleteNeedsTheVersion() {
        JsonNode version = null;
        for (JsonNode parameter : document.path("paths").path("/api/v1/boundaries/{boundaryId}")
                .path("delete").path("parameters")) {
            if (parameter.path("name").asText().equals("version")) {
                version = parameter;
            }
        }
        assertThat(version).isNotNull();
        assertThat(version.path("in").asText()).isEqualTo("query");
        assertThat(version.path("required").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("request fields carry their examples and the rules a form needs")
    void requestExamples() {
        JsonNode prefix = schema("RegisterWarehouseRequest").path("properties").path("prefix");
        assertThat(prefix.path("example").asText()).isEqualTo("HCM");
        assertThat(prefix.path("pattern").asText()).isEqualTo("[A-Za-z0-9]{1,10}");
        assertThat(schema("FootprintRequest").path("properties").path("rotation").path("description").asText())
                .contains("90");
        assertThat(schema("CreateArea").path("properties").path("location").path("description").asText())
                .contains("NON_STORAGE");
    }
}
