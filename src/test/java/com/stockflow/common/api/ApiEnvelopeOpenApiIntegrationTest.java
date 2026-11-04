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
 * {@link ApiResponse} and {@link PageResponse} are generic, and a fixed {@code @Schema(name)} on them
 * made springdoc publish one schema for all of them: every endpoint of every module had its
 * {@code data} documented as whichever type was generated last. This pins one schema per payload.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ApiEnvelopeOpenApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("every ApiResponse<X> and PageResponse<X> has a schema of its own")
    void oneEnvelopeSchemaPerPayload() throws Exception {
        JsonNode schemas = objectMapper.readTree(MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(get("/v3/api-docs/all"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("components").path("schemas");
        List<String> names = new ArrayList<>();
        schemas.fieldNames().forEachRemaining(names::add);

        assertThat(names).doesNotContain("ApiResponse", "Page");
        assertThat(names.stream().filter(name -> name.startsWith("ApiResponse")).count()).isGreaterThan(10);
        assertThat(schemas.path("ApiResponseOrder").path("properties").path("data").path("$ref").asText())
                .endsWith("/Order");
    }
}
