package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-474 TRIAL-FLOW (LP2-9): {@code POST /load-estimates/trial} through the real filter chain, permission aspect,
 * planner and database. It answers the viewer's layout with {@code trial: true}, persists nothing (no estimate row, no
 * audit entry, no event), is allowed by {@code fulfillment-load-estimates:CREATE} alone, applies the estimate's carton
 * cap and does not check the payload.
 */
@SecuredIntegrationTest
class LoadEstimateTrialIntegrationTest {

    private static final String TRIAL = "/api/v1/fulfillment/load-estimates/trial";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private UUID coordinator;
    private UUID staff;
    private UUID container;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        coordinator = user("ORDER_COORDINATOR");
        staff = user("WAREHOUSE_STAFF");
        container = tx.execute(s -> vehicleType("ACTIVE", "26500"));
    }

    private UUID user(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    /** Its own 40-foot high cube, so no other test's edits of the presets reach this one. */
    private UUID vehicleType(String status, String payloadKg) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO fulfillment.vehicle_type (id, code, name, kind, inner_length_mm, inner_width_mm,
                    inner_height_mm, gusset_mm, max_payload_kg, available_count, is_preset, status, created_at)
                VALUES (?, ?, 'Trial test 40HC', 'CONTAINER', 12032, 2352, 2698, 150, ?::numeric, 1, FALSE, ?, NOW())""",
                id, "TRL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT), payloadKg, status);
        return id;
    }

    private static String line(int length, int width, int height, int quantity) {
        return """
                {"name":"Box %d","group":"G1","lengthMm":%d,"widthMm":%d,"heightMm":%d,"quantity":%d,"rotatable":true}"""
                .formatted(length, length, width, height, quantity);
    }

    private static String body(String lines, UUID... types) {
        StringBuilder ids = new StringBuilder();
        for (UUID t : types) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(t).append('"');
        }
        return """
                {"lines":[%s],"vehicleTypeIds":[%s],"selectionMode":"SINGLE_TYPE"}""".formatted(lines, ids);
    }

    private ResultActions trial(Role role, UUID user, String body) throws Exception {
        return http.asRole(role, user, post(TRIAL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    @DisplayName("a coordinator loads a box list: the viewer's layout with trial=true, and nothing is persisted")
    void trialLoadsAndPersistsNothing() throws Exception {
        long estimates = rows("fulfillment.load_estimate");
        long audits = rows("platform.audit_log");
        long events = rows("event_publication");

        JsonNode answer = json.readTree(trial(Role.ORDER_COORDINATOR, coordinator,
                body(line(600, 400, 300, 40) + "," + line(500, 500, 500, 10), container))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trial").value(true))
                .andExpect(jsonPath("$.data.selectionMode").value("SINGLE_TYPE"))
                .andReturn().getResponse().getContentAsString()).get("data");

        JsonNode vehicle = answer.path("vehicles").get(0);
        assertThat(vehicle.path("boxes")).hasSize(50);
        assertThat(vehicle.path("code").asText()).startsWith("TRL");
        assertThat(vehicle.path("innerMm").path("l").asInt()).isEqualTo(12032);
        assertThat(answer.path("unplaced")).isEmpty();
        assertThat(answer.path("meta").path("placedBoxes").asInt()).isEqualTo(50);
        // The sheet's Length runs along the vehicle: the first line's 600 is the box's length, as measured.
        assertThat(vehicle.path("boxes").get(0).path("origMm").path("l").asInt()).isIn(600, 500);
        assertThat(answer.has("id")).isFalse();

        assertThat(rows("fulfillment.load_estimate")).isEqualTo(estimates);
        assertThat(rows("platform.audit_log")).isEqualTo(audits);
        assertThat(rows("event_publication")).isEqualTo(events);
    }

    @Test
    @DisplayName("fulfillment-load-estimates:CREATE decides: a warehouse staff without it is 403, and no row is made")
    void permissionIsChecked() throws Exception {
        long estimates = rows("fulfillment.load_estimate");

        trial(Role.WAREHOUSE_STAFF, staff, body(line(600, 400, 300, 1), container))
                .andExpect(status().isForbidden());
        trial(Role.WAREHOUSE_STAFF, staff, body(line(600, 400, 300, 1))).andExpect(status().isForbidden());

        assertThat(rows("fulfillment.load_estimate")).isEqualTo(estimates);
    }

    @Test
    @DisplayName("the carton cap of an estimate: 3 001 boxes is 422 LOAD_ESTIMATE_TOO_MANY_CARTONS")
    void cartonCapIsEnforced() throws Exception {
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(100, 100, 100, 3_001), container))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_TOO_MANY_CARTONS"));
        trial(Role.ORDER_COORDINATOR, coordinator,
                body(line(100, 100, 100, 2_000) + "," + line(110, 100, 100, 1_001), container))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_TOO_MANY_CARTONS"));
    }

    @Test
    @DisplayName("validation: no lines, a size or quantity that is not positive, an unknown mode: 400 with the field")
    void validationErrors() throws Exception {
        trial(Role.ORDER_COORDINATOR, coordinator, "{\"lines\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines"));
        trial(Role.ORDER_COORDINATOR, coordinator, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(0, 400, 300, 1), container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].lengthMm"));
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(600, 400, 300, 0), container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].quantity"));
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(600, 100_001, 300, 1), container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].widthMm"));
        trial(Role.ORDER_COORDINATOR, coordinator, """
                {"lines":[%s],"selectionMode":"NOPE"}""".formatted(line(600, 400, 300, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("selectionMode"));
    }

    @Test
    @DisplayName("an inactive vehicle type is 409, an unknown one 404, as for an estimate")
    void vehicleTypeErrors() throws Exception {
        UUID inactive = tx.execute(s -> vehicleType("INACTIVE", "26500"));
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(600, 400, 300, 1), inactive))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_INACTIVE"));
        trial(Role.ORDER_COORDINATOR, coordinator, body(line(600, 400, 300, 1), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("the payload is not checked: boxes outweigh a vehicle's payload and still load; size is still checked")
    void payloadIsNotChecked() throws Exception {
        // A payload of 5 g: 30 boxes of the 1 g placeholder weigh 30 g.
        UUID featherweight = tx.execute(s -> vehicleType("ACTIVE", "0.005"));

        JsonNode answer = json.readTree(trial(Role.ORDER_COORDINATOR, coordinator,
                body(line(600, 400, 300, 30), featherweight)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(answer.path("unplaced")).isEmpty();
        assertThat(answer.path("vehicles").get(0).path("boxes")).hasSize(30);

        // A box longer than the vehicle is reported, not loaded.
        JsonNode tooLong = json.readTree(trial(Role.ORDER_COORDINATOR, coordinator,
                body(line(13_000, 400, 300, 2), featherweight)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(tooLong.path("vehicles")).isEmpty();
        assertThat(tooLong.path("unplaced").get(0).path("reason").asText()).isEqualTo("TOO_LARGE");
        assertThat(tooLong.path("unplaced").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(List.of(tooLong.path("unplaced").get(0).path("refs").size())).containsExactly(2);
    }
}
