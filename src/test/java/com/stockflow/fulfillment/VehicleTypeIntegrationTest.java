package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-287 PR B: the vehicle-type catalogue through the real filter chain, permission aspect and database (kltn-docs
 * 09 §3, §4.2, BR-12): the four ISO presets are seeded, a truck is declared and changed, a code never changes, a preset
 * is never deleted, and who may read and who may keep the catalogue is decided by permission codes alone.
 */
@SecuredIntegrationTest
class VehicleTypeIntegrationTest {

    private static final String BASE = "/api/v1/fulfillment/vehicle-types";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private UUID user;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private static String code() {
        return "T" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
    }

    private static String truck(String code) {
        return """
                {"code":"%s","name":"Truck 9 t","kind":"TRUCK","innerLengthMm":6200,"innerWidthMm":2350,
                 "innerHeightMm":2400,"gussetMm":40,"maxPayloadKg":9000,"availableCount":3,"rentalPrice":2500000}""".formatted(code);
    }

    private ResultActions as(Role role, MockHttpServletRequestBuilder request) throws Exception {
        return http.asRole(role, user, request);
    }

    private ResultActions send(Role role, MockHttpServletRequestBuilder request, String body) throws Exception {
        return as(role, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    @DisplayName("the four ISO container presets are seeded, ACTIVE, with their inner dimensions")
    void presetsAreSeeded() throws Exception {
        JsonNode page = data(as(Role.WAREHOUSE_STAFF, get(BASE).param("status", "ACTIVE").param("size", "100"))
                .andExpect(status().isOk()));
        List<String> codes = page.get("items").findValuesAsText("code");
        assertThat(codes).contains("20DC", "40DC", "40HC", "45HC", "PICKUP", "TRUCK-1T25", "TRUCK-2T5", "TRUCK-5T",
                "TRUCK-8T", "TRUCK-15T");
        for (JsonNode row : page.get("items")) {
            if (row.get("code").asText().equals("40HC")) {
                assertThat(row.get("kind").asText()).isEqualTo("CONTAINER");
                assertThat(row.get("preset").asBoolean()).isTrue();
                assertThat(row.get("innerLengthMm").asInt()).isEqualTo(12032);
                assertThat(row.get("innerWidthMm").asInt()).isEqualTo(2352);
                assertThat(row.get("innerHeightMm").asInt()).isEqualTo(2698);
                assertThat(row.get("gussetMm").asInt()).isEqualTo(100);
                assertThat(row.get("maxPayloadKg").decimalValue()).isGreaterThan(java.math.BigDecimal.ZERO);
                assertThat(row.get("availableCount").asInt()).isGreaterThanOrEqualTo(0);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.vehicle_type WHERE is_preset", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT code FROM fulfillment.vehicle_type WHERE kind = 'TRUCK' AND is_preset",
                String.class)).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT kind FROM fulfillment.vehicle_type WHERE code = 'PICKUP'", String.class))
                .isEqualTo("PICKUP");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fulfillment.vehicle_type WHERE kind = 'CONTAINER' AND is_preset""", Integer.class))
                .isEqualTo(4);
        // High cube is taller than the dry container of the same length; 20DC is the shortest.
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT inner_height_mm FROM fulfillment.vehicle_type WHERE code = '40HC')
                     > (SELECT inner_height_mm FROM fulfillment.vehicle_type WHERE code = '40DC')""", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("the warehouse manager declares a truck, reads it, changes it, and retires it (CRUD)")
    void crud() throws Exception {
        String code = code();
        JsonNode created = data(send(Role.WAREHOUSE_MANAGER, post(BASE), truck(code.toLowerCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.preset").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.innerLengthMm").value(6200))
                .andExpect(jsonPath("$.data.gussetMm").value(40)));
        String id = created.get("vehicleTypeId").asText();

        as(Role.ORDER_COORDINATOR, get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxPayloadKg").value(9000))
                .andExpect(jsonPath("$.data.rentalPrice").value(2500000));
        as(Role.WAREHOUSE_STAFF, get(BASE).param("search", code.substring(1)).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].code").value(code));

        send(Role.WAREHOUSE_MANAGER, post(BASE), truck(code)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_CODE_EXISTS"));

        String change = """
                {"code":"%s","name":"Truck 9.5 t","kind":"TRUCK","innerLengthMm":6300,"innerWidthMm":2350,
                 "innerHeightMm":2400,"maxPayloadKg":9500.5,"availableCount":5,"version":%d}"""
                .formatted(code, created.get("version").asLong());
        JsonNode updated = data(send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), change).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Truck 9.5 t"))
                .andExpect(jsonPath("$.data.availableCount").value(5))
                .andExpect(jsonPath("$.data.gussetMm").value(40))
                .andExpect(jsonPath("$.data.rentalPrice").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("ACTIVE")));
        assertThat(updated.get("version").asLong()).isGreaterThan(created.get("version").asLong());

        // The version read is required, and a stale one is refused.
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), change).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));

        String retire = """
                {"name":"Truck 9.5 t","kind":"TRUCK","innerLengthMm":6300,"innerWidthMm":2350,"innerHeightMm":2400,
                 "maxPayloadKg":9500.5,"availableCount":5,"status":"INACTIVE","version":%d}"""
                .formatted(updated.get("version").asLong());
        send(Role.SYSTEM_ADMIN, put(BASE + "/" + id), retire).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        as(Role.WAREHOUSE_STAFF, get(BASE).param("status", "ACTIVE").param("search", code))
                .andExpect(jsonPath("$.data.items").isEmpty());
        as(Role.WAREHOUSE_STAFF, get(BASE).param("status", "INACTIVE").param("search", code))
                .andExpect(jsonPath("$.data.items[0].code").value(code));
        as(Role.WAREHOUSE_STAFF, get(BASE).param("status", "BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        as(Role.WAREHOUSE_STAFF, get(BASE + "/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_NOT_FOUND"));
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + UUID.randomUUID()), retire).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a code never changes, a preset is never deleted, but a preset's availability can be set")
    void presetsAndCodes() throws Exception {
        JsonNode preset = data(as(Role.WAREHOUSE_MANAGER, get(BASE).param("search", "20DC")))
                .get("items").get(0);
        String id = preset.get("vehicleTypeId").asText();
        String body = """
                {"code":"%s","name":"%s","kind":"CONTAINER","innerLengthMm":%d,"innerWidthMm":%d,"innerHeightMm":%d,
                 "maxPayloadKg":%s,"availableCount":%d,"version":%d}""";
        String renamed = body.formatted("OTHER", preset.get("name").asText(), 5898, 2352, 2393, "28200", 7,
                preset.get("version").asLong());
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), renamed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));

        String sameCode = body.formatted("20dc", preset.get("name").asText(), 5898, 2352, 2393, "28200", 7,
                preset.get("version").asLong());
        send(Role.WAREHOUSE_MANAGER, put(BASE + "/" + id), sameCode).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("20DC"))
                .andExpect(jsonPath("$.data.preset").value(true))
                .andExpect(jsonPath("$.data.availableCount").value(7));

        // No DELETE endpoint; the database also refuses, whoever writes (psql, a script).
        as(Role.SYSTEM_ADMIN, delete(BASE + "/" + id)).andExpect(status().is4xxClientError());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM fulfillment.vehicle_type WHERE code = '20DC'"))
                .hasMessageContaining("preset");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("UPDATE fulfillment.vehicle_type SET code = 'X20' WHERE code = '20DC'"))
                .hasMessageContaining("cannot change");
        // A created type cannot become a preset through the API: the body has no such field, and the column is fixed.
        String code = code();
        String id2 = data(send(Role.WAREHOUSE_MANAGER, post(BASE),
                truck(code).replace("\"kind\"", "\"preset\":true,\"kind\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.preset").value(false))).get("vehicleTypeId").asText();
        jdbc.update("DELETE FROM fulfillment.vehicle_type WHERE id = ?::uuid", id2);
    }

    @Test
    @DisplayName("a pickup is a kind of its own, and a type may be declared without a gusset (0)")
    void pickupAndDefaultGusset() throws Exception {
        String body = truck(code()).replace("\"kind\":\"TRUCK\"", "\"kind\":\"PICKUP\"")
                .replace("\"gussetMm\":40,", "");
        send(Role.WAREHOUSE_MANAGER, post(BASE), body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("PICKUP"))
                .andExpect(jsonPath("$.data.gussetMm").value(0));
    }

    @Test
    @DisplayName("validation: dimensions and payload above 0, count not negative, kind and code well-formed")
    void validation() throws Exception {
        for (String bad : List.of(
                truck(code()).replace("\"innerLengthMm\":6200", "\"innerLengthMm\":0"),
                truck(code()).replace("\"innerWidthMm\":2350", "\"innerWidthMm\":-5"),
                truck(code()).replace("\"innerHeightMm\":2400", "\"innerHeightMm\":null"),
                truck(code()).replace("\"maxPayloadKg\":9000", "\"maxPayloadKg\":0"),
                truck(code()).replace("\"availableCount\":3", "\"availableCount\":-1"),
                truck(code()).replace("\"kind\":\"TRUCK\"", "\"kind\":\"BIKE\""),
                truck(code()).replace("\"gussetMm\":40", "\"gussetMm\":-1"),
                truck(code()).replace("\"rentalPrice\":2500000", "\"rentalPrice\":-1"),
                truck("a b"),
                truck(code()).replace("\"name\":\"Truck 9 t\"", "\"name\":\" \""))) {
            send(Role.WAREHOUSE_MANAGER, post(BASE), bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
        String message = json.readTree(send(Role.WAREHOUSE_MANAGER, post(BASE),
                truck(code()).replace("\"innerLengthMm\":6200", "\"innerLengthMm\":0")).andReturn().getResponse()
                .getContentAsString()).get("fieldErrors").get(0).get("field").asText();
        assertThat(message).isEqualTo("innerLengthMm");
        // The table says the same, for a writer that skips the API.
        for (String check : List.of("inner_length_mm = 0", "inner_width_mm = 0", "inner_height_mm = 0",
                "max_payload_kg = 0", "available_count = -1", "gusset_mm = -1")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                            "UPDATE fulfillment.vehicle_type SET " + check + " WHERE code = '45HC'"))
                    .hasMessageContaining("ck_vehicle_type");
        }
    }

    @Test
    @DisplayName("staff and the coordinator read but cannot keep the catalogue; anonymous is 401; others are 403")
    void permissions() throws Exception {
        String id = data(as(Role.WAREHOUSE_STAFF, get(BASE).param("search", "45HC"))).get("items").get(0)
                .get("vehicleTypeId").asText();
        String body = truck(code());
        String update = """
                {"name":"x","kind":"TRUCK","innerLengthMm":1,"innerWidthMm":1,"innerHeightMm":1,"maxPayloadKg":1,
                 "availableCount":1,"version":0}""";

        for (Role reader : List.of(Role.WAREHOUSE_STAFF, Role.WAREHOUSE_MANAGER, Role.ORDER_COORDINATOR,
                Role.SYSTEM_ADMIN)) {
            as(reader, get(BASE)).andExpect(status().isOk());
            as(reader, get(BASE + "/" + id)).andExpect(status().isOk());
        }
        for (Role cannotUpdate : List.of(Role.WAREHOUSE_STAFF, Role.ORDER_COORDINATOR)) {
            send(cannotUpdate, post(BASE), body).andExpect(status().isForbidden());
            send(cannotUpdate, put(BASE + "/" + id), update).andExpect(status().isForbidden());
        }
        for (Role noAccess : List.of(Role.CUSTOMER, Role.ACCOUNTANT, Role.SALES_STAFF, Role.QC_STAFF)) {
            as(noAccess, get(BASE)).andExpect(status().isForbidden());
            as(noAccess, get(BASE + "/" + id)).andExpect(status().isForbidden());
            send(noAccess, post(BASE), body).andExpect(status().isForbidden());
        }
        http.anonymous(get(BASE)).andExpect(status().isUnauthorized());
        http.anonymous(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        http.anonymous(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.vehicle_type WHERE name = 'x'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("the grants: VIEW_PAGE and READ for staff, manager, coordinator, admin; UPDATE for manager and admin; role versions bumped")
    void grants() {
        List<String> holders = jdbc.queryForList("""
                SELECT r.code || ':' || p.action FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.resource = 'fulfillment-vehicle-types' ORDER BY 1""", String.class);
        assertThat(holders).containsExactlyInAnyOrder(
                "WAREHOUSE_STAFF:VIEW_PAGE", "WAREHOUSE_STAFF:READ",
                "WAREHOUSE_MANAGER:VIEW_PAGE", "WAREHOUSE_MANAGER:READ", "WAREHOUSE_MANAGER:UPDATE",
                "ORDER_COORDINATOR:VIEW_PAGE", "ORDER_COORDINATOR:READ",
                "SYSTEM_ADMIN:VIEW_PAGE", "SYSTEM_ADMIN:READ", "SYSTEM_ADMIN:UPDATE");
        assertThat(jdbc.queryForObject("""
                SELECT min(version) FROM identity.app_role
                 WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN')""",
                Long.class)).isGreaterThanOrEqualTo(1L);
    }
}
