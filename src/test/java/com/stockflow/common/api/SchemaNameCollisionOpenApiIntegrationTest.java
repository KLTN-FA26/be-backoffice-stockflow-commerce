package com.stockflow.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.config.NestedSchemaNameConverter;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * swagger-core names a schema by the simple class name, so two nested records of the same name in two DTOs became
 * one schema and the one generated last overwrote the other, silently: the JSON on the wire stays right, only the
 * document lies. A putaway trip stop's {@code path} was documented as a list of carrier pickup addresses (#445), and
 * 58 more nested records the same way (#459). {@link NestedSchemaNameConverter} names a nested type after the types
 * enclosing it; this pins the rule on the document springdoc serves, and asserts that no schema name went to two types
 * in any group.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SchemaNameCollisionOpenApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired NestedSchemaNameConverter schemaNames;
    @Autowired SpringDocConfigProperties springDoc;

    private JsonNode schemasOf(MockMvc mvc, String group) throws Exception {
        return objectMapper.readTree(mvc.perform(get("/v3/api-docs/" + group))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("components").path("schemas");
    }

    @Test
    @DisplayName("a trip stop's path is a list of map points, and a carrier's pickup point keeps its own schema")
    void mapPointAndPickupPointAreTwoSchemas() throws Exception {
        JsonNode schemas = schemasOf(MockMvcBuilders.webAppContextSetup(context).build(), "all");

        String pathItems = schemas.path("PutawayTripResponseStop").path("properties").path("path").path("items")
                .path("$ref").asText();
        assertThat(pathItems).endsWith("/PutawayTripResponseMapPoint");
        assertThat(fieldsOf(schemas.path("PutawayTripResponseMapPoint"))).containsExactlyInAnyOrder("x", "y");

        assertThat(fieldsOf(schemas.path("CarrierPickupPointsRequestPickupPoint")))
                .contains("warehouseId", "provinceCode", "address", "contactName", "contactPhone");
        assertThat(schemas.path("CarrierPickupPointsRequest").path("properties").path("pickupPoints").path("items")
                .path("$ref").asText()).endsWith("/CarrierPickupPointsRequestPickupPoint");
        assertThat(schemas.has("Point")).isFalse();
    }

    @Test
    @DisplayName("nested records of one simple name each keep their own fields: an order's shipment is not a load's")
    void nestedRecordsOfOneNameKeepTheirOwnFields() throws Exception {
        JsonNode schemas = schemasOf(MockMvcBuilders.webAppContextSetup(context).build(), "all");

        assertThat(schemas.has("Line")).isFalse();
        assertThat(schemas.has("Shipment")).isFalse();
        // OrderResponse is published as Order, and its Shipment was named OrderShipment by hand before the rule.
        assertThat(fieldsOf(schemas.path("OrderShipment"))).contains("amount", "lostClosable")
                .doesNotContain("loadedCount");
        assertThat(fieldsOf(schemas.path("LoadResponseShipment"))).contains("loadedCount", "packages")
                .doesNotContain("lostClosable");
        assertThat(fieldsOf(schemas.path("GoodsReceiptResponseLine"))).isNotEmpty()
                .isNotEqualTo(fieldsOf(schemas.path("PaymentReceiptResponseLine")));
    }

    @Test
    @DisplayName("in every group of the document, no schema name is given to two types")
    void noSchemaNameIsGivenToTwoTypes() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        List<String> groups = springDoc.getGroupConfigs().stream()
                .map(SpringDocConfigProperties.GroupConfig::getGroup).toList();
        assertThat(groups).contains("all");

        for (String group : groups) {
            schemasOf(mvc, group);
        }

        assertThat(schemaNames.collisions()).as("schema names given to more than one type").isEmpty();
    }

    private static List<String> fieldsOf(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }
}
