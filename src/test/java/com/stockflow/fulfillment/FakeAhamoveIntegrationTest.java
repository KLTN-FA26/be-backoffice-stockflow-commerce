package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.common.domain.Sku;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fake Ahamove (stockflow.fulfillment.ahamove.mode=fake) through the real filter chain, permission aspect and
 * database - the whole API_BOOKING demo with no network and no secrets key: a load booked at the seeded AHAMOVE carrier
 * gets a fake order id for its waybill number; a driver takes it (name and phone on the load, so the handover can be
 * signed); the pickup reported before the handover is for review and is put back; after the handover the order is
 * delivered or fails through the system's own webhook pipeline; a cancel works before pickup; Ahamove cancelling the
 * order sends the load back to PLANNED; and the remote control is the warehouse manager's.
 *
 * <p>Its own context, hence its own database. The carrier is the one the demo seed makes
 * ({@code db/demo/V20261203000400__demo_fake_ahamove.sql}, run here over an AHAMOVE row that is still MANUAL, as the
 * tools seed leaves it).</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.fulfillment.load-planning.solver-wall-limit=PT2S",
        "stockflow.test.context=fake-ahamove",
        "stockflow.fulfillment.ahamove.mode=fake",
        // No secrets key at all: the fake must not need one (the test profile's fixed key is blanked).
        "stockflow.security.secrets.key=",
        "stockflow.upload-inspection.enabled=false",
        "stockflow.fulfillment.carrier-booking-sweep-interval=PT24H"})
class FakeAhamoveIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PLANS = BASE + "/load-plans";
    private static final String LOADS = BASE + "/loads";
    private static final String FAKE = BASE + "/dev/fake-ahamove/orders";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID manager;
    private UUID picker;
    private UUID dispatcher;
    private UUID truck1t25;
    private UUID carrier;

    @BeforeEach
    void setUp() {
        CartonStationSupport.giveCartonsASize(tx, jdbc);
        coordinator = staff("WAREHOUSE_MANAGER", HCM);
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        truck1t25 = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'",
                UUID.class);
        carrier = seededAhamove();
    }

    /**
     * The tools seed's AHAMOVE (MANUAL), then the demo seed over it - as a local database gets it. The script is run
     * twice: it is meant to be repeatable.
     */
    private UUID seededAhamove() {
        // The pool does not auto-commit (CLAUDE.md, persistence traps): a write outside a transaction is lost.
        String script;
        try {
            script = new String(new ClassPathResource("db/demo/V20261203000400__demo_fake_ahamove.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
        return tx.execute(s -> {
            jdbc.update("""
                    INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status,
                                                     connection_type, version, created_at, created_by)
                    VALUES (?, 'AHAMOVE', 'Ahamove', 'Ahamove Partner Support', '1900545411', 'ACTIVE', 'MANUAL', 0,
                            NOW(), 'seed')
                    ON CONFLICT (code) DO NOTHING""", Identifiers.newId());
            jdbc.execute(script);
            jdbc.execute(script);
            return jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'AHAMOVE'", UUID.class);
        });
    }

    // ---- fixtures (as FtlDispatchIntegrationTest's) ------------------------------------------------

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return mvc.perform(request.header("Authorization", "Bearer " + value));
    }

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(dispatcher, "WAREHOUSE_STAFF", request);
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "WAREHOUSE_MANAGER", request);
    }

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(picker, "WAREHOUSE_STAFF", request);
    }

    /** The warehouse manager at the remote control. */
    private ResultActions remote(MockHttpServletRequestBuilder request) throws Exception {
        return as(manager, "WAREHOUSE_MANAGER", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private ConfirmedOrders confirmedOrders;
    private CustomerSkuRows.Issued sofa;

    private OrderSummary ftlOrder() {
        if (confirmedOrders == null) {
            confirmedOrders = new ConfirmedOrders(context);
        }
        if (sofa == null) {
            sofa = CustomerSkuRows.like(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, SOFA.code(), 2);
        }
        CustomerSkuRows.stock(jdbc, tx, sofa.sku(), 2);
        ConfirmedOrders.Signed signed = confirmedOrders.signed(DemoData.CUSTOMER_ID, ConfirmedOrders.paidOnSigning(),
                ConfirmedOrders.Line.cartons(sofa, 1, 6_000_000));
        return confirmedOrders.release(confirmedOrders.pay(signed).orderId(), HCM, coordinator);
    }

    private UUID packed() throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + ftlOrder().orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId")
                .asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, lpn AS scan FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\"" + line.get("scan")
                            + "\"}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        return CartonStationSupport.ship(this::packer, jdbc, json, task, null);
    }

    private JsonNode step(JsonNode plan, String path, String content) throws Exception {
        return data(dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + path), content))
                .andExpect(status().isOk()));
    }

    private static String version(JsonNode plan) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + "}";
    }

    /** A shipment to Ho Chi Minh City (province 79, where the seeded pickup is), planned on a 1.25 t truck: its load id. */
    private String plannedLoad(UUID shipment) throws Exception {
        JsonNode plan = data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":[\"" + shipment + "\"],"
                + "\"vehicleTypeIds\":[\"" + truck1t25 + "\"],\"selectionMode\":\"SINGLE_TYPE\"}"))
                .andExpect(status().isCreated()));
        plan = step(plan, "/calculation", version(plan));
        plan = step(plan, "/confirmation", version(plan));
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                UPDATE ordering.customer_order SET shipping_recipient_name = 'Lan', shipping_phone = '0912345678',
                    shipping_line1 = '2 Hai Bà Trưng', shipping_ward_code = '26740', shipping_ward_name = 'Bến Nghé',
                    shipping_province_code = '79', shipping_province_name = 'Hồ Chí Minh', shipping_country_code = 'VN'
                 WHERE id = (SELECT order_id FROM fulfillment.shipment WHERE id = ?)""", shipment);
        });
        return plan.path("vehicles").get(0).path("load").path("loadId").asText();
    }

    private JsonNode load(String loadId) throws Exception {
        return data(dispatch(get(LOADS + "/" + loadId)).andExpect(status().isOk()));
    }

    private String shipmentStatus(UUID shipment) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, shipment);
    }

    private void loadAndSign(String loadId, String plate) throws Exception {
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        List<String> labels = new ArrayList<>();
        load(loadId).path("shipments").forEach(s -> s.path("packages").forEach(p -> labels.add(
                p.path("labelCode").asText())));
        for (String label : labels) {
            dispatch(body(post(LOADS + "/" + loadId + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                    + "\",\"packageCode\":\"" + label + "\"}")).andExpect(status().isOk());
        }
        long version = load(loadId).path("version").asLong();
        dispatch(body(post(LOADS + "/" + loadId + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"vehiclePlate\":\"" + plate
                + "\",\"signed\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"));
    }

    // ---- the fake, as the demo drives it -------------------------------------------------------

    /** The booking of a load at the seeded carrier: the fake order id that is the waybill number. */
    private String book(String loadId) throws Exception {
        JsonNode booked = data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\""
                + carrier + "\"}")).andExpect(status().isOk()));
        assertThat(booked.path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        assertThat(booked.path("carrierBooking").path("status").asText()).isEqualTo("BOOKED");
        String order = booked.path("waybillNo").asText();
        assertThat(order).matches("^FK[A-Z0-9]{12}$");
        return order;
    }

    private JsonNode event(String order, String step, String extra) throws Exception {
        return data(remote(body(post(FAKE + "/" + order + "/events"), "{\"step\":\"" + step + "\"" + extra + "}"))
                .andExpect(status().isOk()));
    }

    private String result(JsonNode played) {
        return played.path("callbacks").get(0).path("result").asText();
    }

    private String fakeStatus(String order) throws Exception {
        return data(remote(get(FAKE + "/" + order)).andExpect(status().isOk())).path("status").asText();
    }

    // ---- the demo ----------------------------------------------------------------------------

    @Test
    @DisplayName("the demo seed makes AHAMOVE an API_BOOKING carrier with placeholder secrets, service codes per vehicle "
            + "type, the pickup point and the failure map, and running it again changes nothing")
    void theSeed() {
        Map<String, Object> row = jdbc.queryForMap("SELECT connection_type, webhook_enabled, api_environment, "
                + "webhook_auth_header, failure_reason_map::text AS map FROM fulfillment.carrier WHERE id = ?", carrier);
        assertThat(row.get("connection_type")).isEqualTo("API_BOOKING");
        assertThat(row.get("webhook_enabled")).isEqualTo(true);
        assertThat(row.get("api_environment")).isEqualTo("STAGING");
        assertThat((String) row.get("map")).contains("CUSTOMER_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_service_code WHERE carrier_id = ?",
                Long.class, carrier)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fulfillment.carrier_pickup_point p JOIN warehouse.warehouse w
                  ON w.id = p.warehouse_id WHERE p.carrier_id = ? AND w.prefix = 'HCM' AND p.province_code = '79'""",
                Long.class, carrier)).isOne();
    }

    @Test
    @DisplayName("book, a driver, the pickup refused before the handover and put back, the handover (the driver's name is "
            + "on the load from the fake), then delivered: the shipment is DELIVERED through the webhook pipeline")
    void bookedDrivenAndDelivered() throws Exception {
        UUID shipment = packed();
        String loadId = plannedLoad(shipment);
        String order = book(loadId);
        assertThat(data(remote(get(FAKE)).andExpect(status().isOk())).toString()).contains(order);
        // The remote control is the warehouse manager's, as the error list is.
        dispatch(get(FAKE)).andExpect(status().isForbidden());

        assertThat(result(event(order, "ASSIGNING", ""))).isEqualTo("IGNORED");
        JsonNode accepted = event(order, "ACCEPT", ",\"driverName\":\"Le Van Tai\",\"driverPhone\":\"0911222333\","
                + "\"vehiclePlate\":\"51C-123.45\"");
        assertThat(result(accepted)).isEqualTo("APPLIED");
        assertThat(accepted.path("order").path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Le Van Tai");
        assertThat(load(loadId).path("driverPhone").asText()).isEqualTo("0911222333");

        // The vehicle has not left: the system will not take a pickup yet, and the fake puts the step back.
        JsonNode early = event(order, "PICKUP", "");
        assertThat(result(early)).isEqualTo("NEEDS_REVIEW");
        assertThat(early.path("order").path("status").asText()).isEqualTo("ACCEPTED");

        loadAndSign(loadId, "51C-123.45");
        JsonNode played = data(remote(body(post(FAKE + "/" + order + "/play"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk()));
        assertThat(played.path("callbacks")).extracting(c -> c.path("step").asText())
                .containsExactly("PICKUP", "DELIVER", "COMPLETE");
        assertThat(played.path("callbacks")).extracting(c -> c.path("result").asText())
                .allMatch(r -> r.equals("APPLIED") || r.equals("IGNORED"));
        assertThat(played.path("order").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_api_call WHERE carrier_id = ? "
                + "AND operation = 'BOOK' AND outcome = 'OK'", Long.class, carrier)).isPositive();
    }

    @Test
    @DisplayName("a drop-off that fails: DELIVERY_FAILED with Ahamove's wording mapped by the carrier's table")
    void aFailedDelivery() throws Exception {
        UUID shipment = packed();
        String loadId = plannedLoad(shipment);
        String order = book(loadId);
        assertThat(result(event(order, "ACCEPT", ""))).isEqualTo("APPLIED");
        loadAndSign(loadId, "51C-123.45");
        assertThat(result(event(order, "PICKUP", ""))).isEqualTo("APPLIED");

        assertThat(result(event(order, "FAIL", ",\"reason\":\"CUSTOMER_NOT_AVAILABLE\""))).isEqualTo("APPLIED");

        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERY_FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("CUSTOMER_ABSENT");
    }

    @Test
    @DisplayName("cancel works before pickup (the fake's order is cancelled, the load back to PLANNED); Ahamove "
            + "cancelling the order itself sends the load back to PLANNED too")
    void cancelling() throws Exception {
        UUID shipment = packed();
        String loadId = plannedLoad(shipment);
        String order = book(loadId);
        assertThat(result(event(order, "ACCEPT", ""))).isEqualTo("APPLIED");

        JsonNode cancelled = data(dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"),
                "{\"reason\":\"change of plan\"}")).andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("PLANNED");
        assertThat(fakeStatus(order)).isEqualTo("CANCELLED");

        String second = book(loadId);
        assertThat(second).isNotEqualTo(order);
        assertThat(result(event(second, "CARRIER_CANCEL", ",\"reason\":\"no driver\""))).isEqualTo("APPLIED");
        assertThat(load(loadId).path("status").asText()).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.carrier_booking WHERE carrier_order_id = ?",
                String.class, second)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("the carrier screen's service list comes from the fake, and a key cannot be saved without a secrets key "
            + "(which the fake does not need)")
    void servicesAndNoSecretsKey() throws Exception {
        remote(get(BASE + "/carriers/" + carrier + "/services").param("cityId", "HAN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceCode").value("HAN-TRUCK-500"));
        remote(body(post(BASE + "/carriers"), "{\"code\":\"AHA-KEY\",\"name\":\"Ahamove again\",\"connection\":"
                + "{\"connectionType\":\"API_BOOKING\",\"webhookAuthHeader\":\"apikey\",\"webhookAuthValue\":"
                + "\"webhook-credential-0123456789\",\"apiEnvironment\":\"STAGING\",\"apiAccount\":\"84908000000\","
                + "\"apiKey\":\"aha-key-0123456789\"}}")).andExpect(status().isServiceUnavailable());
    }
}
