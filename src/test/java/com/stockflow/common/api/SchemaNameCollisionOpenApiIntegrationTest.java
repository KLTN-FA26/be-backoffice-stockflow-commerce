package com.stockflow.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * springdoc names a schema by the simple class name, so two nested records of the same name in two DTOs become one
 * schema and the one generated last overwrites the other, silently: the JSON on the wire stays right, only the
 * document lies. A putaway trip stop's {@code path} was documented as a list of carrier pickup addresses (#445).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SchemaNameCollisionOpenApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("a trip stop's path is a list of map points, and a carrier's pickup point keeps its own schema")
    void mapPointAndPickupPointAreTwoSchemas() throws Exception {
        JsonNode schemas = objectMapper.readTree(MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(get("/v3/api-docs/all"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("components").path("schemas");

        String pathItems = schemas.path("Stop").path("properties").path("path").path("items").path("$ref").asText();
        assertThat(pathItems).endsWith("/MapPoint");
        assertThat(fieldsOf(schemas.path("MapPoint"))).containsExactlyInAnyOrder("x", "y");

        assertThat(fieldsOf(schemas.path("PickupPoint")))
                .contains("warehouseId", "provinceCode", "address", "contactName", "contactPhone");
        assertThat(schemas.path("CarrierPickupPointsRequest").path("properties").path("pickupPoints").path("items")
                .path("$ref").asText()).endsWith("/PickupPoint");
        assertThat(schemas.has("Point")).isFalse();
    }

    private static List<String> fieldsOf(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }
}
