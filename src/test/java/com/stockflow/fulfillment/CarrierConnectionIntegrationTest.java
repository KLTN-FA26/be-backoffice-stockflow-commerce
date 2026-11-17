package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3-1: a carrier's connection settings and service codes through the real filter chain, permission aspect and database
 * (kltn-docs 09 §3, §4.1 step 2, BR-21). The secrets go in and never come out - not in a response, not in clear in the
 * table - and are kept when an update leaves them out; a carrier's service codes are kept for API_BOOKING carriers only.
 */
@SecuredIntegrationTest
class CarrierConnectionIntegrationTest {

    private static final String BASE = "/api/v1/fulfillment/carriers";
    private static final String API_KEY = "aha-staging-key-0123456789";
    private static final String HEADER_CREDENTIAL = "webhook-credential-abcdefghij";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;

    private SecuredMvc http;
    private UUID user;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private static String code() {
        return "C" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
    }

    private ResultActions send(Role role, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return http.asRole(role, user, request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static Map<String, Object> carrier(String code, Map<String, Object> connection, Long version) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Ahamove");
        body.put("contactName", "Điều phối");
        body.put("contactPhone", "19001272");
        if (connection != null) {
            body.put("connection", connection);
        }
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    private static Map<String, Object> booking(String apiKey, String credential) {
        Map<String, Object> connection = new java.util.LinkedHashMap<>();
        connection.put("connectionType", "API_BOOKING");
        connection.put("webhookAuthHeader", "apikey");
        connection.put("apiEnvironment", "STAGING");
        connection.put("apiAccount", "84908000000");
        connection.put("failureReasonMap", Map.of("Khách không nghe máy", "CUSTOMER_ABSENT"));
        if (apiKey != null) {
            connection.put("apiKey", apiKey);
        }
        if (credential != null) {
            connection.put("webhookAuthValue", credential);
        }
        return connection;
    }

    private Map<String, Object> sealed(UUID carrierId) {
        return jdbc.queryForMap("SELECT connection_type, webhook_enabled, api_key_sealed, webhook_auth_value_sealed, "
                + "webhook_secret_sealed, version FROM fulfillment.carrier WHERE id = ?", carrierId);
    }

    @Test
    @DisplayName("an API_BOOKING carrier's secrets are sealed in the table and never returned; reads show flags only")
    void secretsGoInAndNeverComeOut() throws Exception {
        ResultActions created = send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(code(), booking(API_KEY,
                HEADER_CREDENTIAL), null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.connection.connectionType").value("API_BOOKING"))
                .andExpect(jsonPath("$.data.connection.webhookEnabled").value(true))
                .andExpect(jsonPath("$.data.connection.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.connection.webhookAuthValueConfigured").value(true))
                .andExpect(jsonPath("$.data.connection.webhookSecretConfigured").value(false))
                .andExpect(jsonPath("$.data.connection.apiAccount").value("84908000000"))
                .andExpect(jsonPath("$.data.connection.failureReasonMap['Khách không nghe máy']")
                        .value("CUSTOMER_ABSENT"));
        String body = created.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(API_KEY).doesNotContain(HEADER_CREDENTIAL);
        UUID id = UUID.fromString(data(created).get("carrierId").asText());

        Map<String, Object> row = sealed(id);
        assertThat((String) row.get("api_key_sealed")).startsWith("v1:").doesNotContain(API_KEY);
        assertThat(cipher.open((String) row.get("api_key_sealed"), "carrier:" + id + ":api-key")).isEqualTo(API_KEY);
        assertThat(cipher.open((String) row.get("webhook_auth_value_sealed"), "carrier:" + id + ":webhook-auth-value"))
                .isEqualTo(HEADER_CREDENTIAL);

        String read = http.asRole(Role.WAREHOUSE_STAFF, user, get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.apiKeyConfigured").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain(API_KEY).doesNotContain(HEADER_CREDENTIAL).doesNotContain("v1:");
    }

    @Test
    @DisplayName("an update that leaves the secrets out keeps them; switching to MANUAL clears every one")
    void secretsAreKeptThenCleared() throws Exception {
        String code = code();
        UUID id = UUID.fromString(data(send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(code,
                booking(API_KEY, HEADER_CREDENTIAL), null)).andExpect(status().isCreated())).get("carrierId").asText());
        Map<String, Object> before = sealed(id);

        // No connection at all: the settings stay as they are.
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), carrier(code, null, (Long) before.get("version")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connection.connectionType").value("API_BOOKING"));
        // The connection without its secrets: the sealed values are kept as they were.
        Map<String, Object> kept = sealed(id);
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), carrier(code, booking(null, null),
                (Long) kept.get("version"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.apiKeyConfigured").value(true));
        Map<String, Object> after = sealed(id);
        assertThat(after.get("api_key_sealed")).isEqualTo(before.get("api_key_sealed"));
        assertThat(after.get("webhook_auth_value_sealed")).isEqualTo(before.get("webhook_auth_value_sealed"));

        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), carrier(code, Map.of("connectionType", "MANUAL"),
                (Long) after.get("version"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.connectionType").value("MANUAL"))
                .andExpect(jsonPath("$.data.connection.webhookEnabled").value(false))
                .andExpect(jsonPath("$.data.connection.apiKeyConfigured").value(false));
        Map<String, Object> manual = sealed(id);
        assertThat(manual.get("api_key_sealed")).isNull();
        assertThat(manual.get("webhook_auth_value_sealed")).isNull();
        assertThat(manual.get("webhook_enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("a connection missing what its type needs is 400 naming each field, and nothing is saved")
    void incompleteConnectionIsRefused() throws Exception {
        String code = code();
        send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(code, Map.of("connectionType", "API_BOOKING"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "webhookAuthHeader", "webhookAuthValue", "apiEnvironment", "apiAccount", "apiKey")));
        send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(code, Map.of("connectionType", "WEBHOOK_LOAD",
                "webhookSecret", "too-short"), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("webhookSecret"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier WHERE code = ?", Long.class, code))
                .isZero();
    }

    @Test
    @DisplayName("service codes are kept for an API_BOOKING carrier only, one per vehicle type and province")
    void serviceCodes() throws Exception {
        UUID truck = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'",
                UUID.class);
        UUID van = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5'", UUID.class);
        String manualCode = code();
        UUID manual = UUID.fromString(data(send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(manualCode, null, null))
                .andExpect(status().isCreated())).get("carrierId").asText());
        Map<String, Object> codes = Map.of("codes", java.util.List.of(
                Map.of("vehicleTypeId", truck, "provinceCode", "79", "carrierCityCode", "SGN",
                        "serviceCode", "SGN-TRUCK-1000"),
                Map.of("vehicleTypeId", van, "provinceCode", "79", "carrierCityCode", "SGN",
                        "serviceCode", "SGN-TRUCK-2000")));
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + manual + "/service-codes"), codes)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_NOT_API_BOOKING"));

        UUID aha = UUID.fromString(data(send(Role.WAREHOUSE_MANAGER, post(BASE), carrier(code(),
                booking(API_KEY, HEADER_CREDENTIAL), null)).andExpect(status().isCreated())).get("carrierId").asText());
        send(Role.WAREHOUSE_STAFF, put(BASE + "/" + aha + "/service-codes"), codes).andExpect(status().isForbidden());
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + aha + "/service-codes"), codes).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].serviceCode").value("SGN-TRUCK-1000"))
                .andExpect(jsonPath("$.data[0].vehicleTypeCode").value("TRUCK-1T25"));
        http.asRole(Role.WAREHOUSE_STAFF, user, get(BASE + "/" + aha + "/service-codes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].vehicleTypeCode").value("TRUCK-2T5"));

        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + aha + "/service-codes"), Map.of("codes", java.util.List.of(
                Map.of("vehicleTypeId", truck, "provinceCode", "79", "carrierCityCode", "SGN", "serviceCode", "A"),
                Map.of("vehicleTypeId", truck, "provinceCode", "79", "carrierCityCode", "SGN", "serviceCode", "B"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field")
                        .value("codes[1].provinceCode"));
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + aha + "/service-codes"), Map.of("codes", java.util.List.of(
                Map.of("vehicleTypeId", UUID.randomUUID(), "provinceCode", "79", "carrierCityCode", "SGN",
                        "serviceCode", "A")))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_NOT_FOUND"));
        // The refused replacements changed nothing; an empty list removes them all.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_service_code WHERE carrier_id = ?",
                Long.class, aha)).isEqualTo(2);
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + aha + "/service-codes"), Map.of("codes", java.util.List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }
}
