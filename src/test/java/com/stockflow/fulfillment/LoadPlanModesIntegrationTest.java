package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.order.api.OrderSummary;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WF04 load planning against the docs (RTM 09-shipping-A): the selection modes a load plan and an order estimate store
 * (09 §4.2, WF4-LP-MODES: "Giữ nguyên dòng", "Vượt sức chứa"), a shipment taken out of a PLANNED plan that only sends
 * its vehicles to be recalculated (09 §4.2 step 9, 09A-158), a cancelled booking kept in the load's history (09 §4.2
 * step 6, 09A-125) and an order estimate built from the LPN cartons allocated to it (09 §4.0 step 2, 09A-84).
 *
 * <p>The sofa carton weighs 1 200 kg here: a 2.5 t truck carries two of them, or one with a little else.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.fulfillment.load-planning.solver-wall-limit=PT4S",
        "stockflow.test.context=load-plan-modes",
        "stockflow.upload-inspection.enabled=false"})
class LoadPlanModesIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PLANS = BASE + "/load-plans";
    private static final String LOADS = BASE + "/loads";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;

    private UUID manager;
    private UUID picker;
    private UUID dispatcher;
    private UUID truck2t5;
    private ConfirmedOrders confirmedOrders;
    private final Map<String, CustomerSkuRows.Issued> skus = new HashMap<>();

    @BeforeEach
    void setUp() {
        CartonStationSupport.giveCartonsASize(tx, jdbc);
        CartonStationSupport.giveSofaCartonsAWeight(tx, jdbc, "1200");
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        truck2t5 = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-2T5'", UUID.class);
        confirmedOrders = new ConfirmedOrders(context);
    }

    // ---- fixtures ----------------------------------------------------------------------------

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
        return as(manager, "WAREHOUSE_MANAGER", request);
    }

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(picker, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    /** The customer's own SKU handling like the catalogue's {@code like}, in cartons of two pieces. */
    private CustomerSkuRows.Issued sku(String like) {
        return skus.computeIfAbsent(like, l -> CustomerSkuRows.like(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, l, 2));
    }

    /** A paid order released at HCM: {@code sofas} sofa cartons and {@code tables} table cartons, two lines. */
    private OrderSummary order(int sofas, int tables) {
        List<ConfirmedOrders.Line> lines = new java.util.ArrayList<>();
        if (sofas > 0) {
            CustomerSkuRows.stock(jdbc, tx, sku("SOFA-3S-GREY").sku(), 2 * sofas);
            lines.add(ConfirmedOrders.Line.cartons(sku("SOFA-3S-GREY"), sofas, 6_000_000));
        }
        if (tables > 0) {
            CustomerSkuRows.stock(jdbc, tx, sku("TABLE-OAK-160").sku(), 2 * tables);
            lines.add(ConfirmedOrders.Line.cartons(sku("TABLE-OAK-160"), tables, 3_000_000));
        }
        ConfirmedOrders.Signed signed = confirmedOrders.signed(DemoData.CUSTOMER_ID, ConfirmedOrders.paidOnSigning(),
                lines.toArray(ConfirmedOrders.Line[]::new));
        return confirmedOrders.release(confirmedOrders.pay(signed).orderId(), HCM, manager);
    }

    /** The order picked by LPN and checked at the station: one shipment of all its cartons. */
    private UUID packed(OrderSummary order) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
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

    private JsonNode create(List<UUID> shipments, String mode) throws Exception {
        return data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"shipmentIds\":" + ids(shipments) + ",\"vehicleTypeIds\":[\"" + truck2t5
                + "\"],\"selectionMode\":\"" + mode + "\"}")).andExpect(status().isCreated()));
    }

    private static String ids(List<UUID> ids) {
        return "[" + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()) + "]";
    }

    private JsonNode step(JsonNode plan, String path, String content) throws Exception {
        return data(dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + path), content))
                .andExpect(status().isOk()));
    }

    private static String version(JsonNode plan) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + "}";
    }

    private JsonNode inputs(JsonNode plan, List<UUID> shipments, UUID type, String mode) throws Exception {
        return data(dispatch(body(put(PLANS + "/" + plan.path("loadPlanId").asText() + "/inputs"),
                "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"shipmentIds\":" + ids(shipments)
                        + ",\"vehicleTypeIds\":[\"" + type + "\"],\"selectionMode\":\"" + mode + "\"}"))
                .andExpect(status().isOk()));
    }

    /** A 2.5 t truck type of its own with one vehicle on offer, so no other test's fleet reaches this one. */
    private UUID oneTruck() {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO fulfillment.vehicle_type (id, code, name, kind, "
                + "inner_length_mm, inner_width_mm, inner_height_mm, gusset_mm, max_payload_kg, available_count, "
                + "is_preset, status, created_at) VALUES (?, ?, 'One 2.5 t truck', 'TRUCK', 4300, 1800, 1800, 0, 2500, "
                + "1, FALSE, 'ACTIVE', NOW())", id, "ONE" + id.toString().substring(0, 6).toUpperCase(
                java.util.Locale.ROOT)));
        return id;
    }

    /** Package id to the vehicle seq it was placed on, of the plan's parcels. */
    private static Map<String, Integer> seqOf(JsonNode plan) {
        Map<String, Integer> out = new HashMap<>();
        plan.path("packages").forEach(p -> {
            if (p.hasNonNull("vehicleSeq")) {
                out.put(p.path("packageId").asText(), p.path("vehicleSeq").asInt());
            }
        });
        return out;
    }

    // ---- 09 §4.2 selection modes -------------------------------------------------------------

    @Test
    @DisplayName("09 §4.2: \"Giữ nguyên dòng\" keeps each order line's cartons on one vehicle; \"Vượt sức chứa\" loads "
            + "the vehicles on offer and reports the rest; a mode the docs do not have is refused; the mode is stored "
            + "and shown")
    void keepLinesAndOverCapacity() throws Exception {
        // Two heavy sofas (2 400 kg) and three tables (114 kg): the tables do not join the sofas' 2.5 t truck.
        UUID shipment = packed(order(2, 3));

        JsonNode plan = create(List.of(shipment), "KEEP_LINES");
        assertThat(plan.path("selectionMode").asText()).isEqualTo("KEEP_LINES");
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        assertThat(plan.path("unplacedCount").asInt()).isZero();
        Map<String, Set<Integer>> vehiclesOfLine = new HashMap<>();
        plan.path("packages").forEach(p -> vehiclesOfLine.computeIfAbsent(p.path("partName").asText(),
                k -> new HashSet<>()).add(p.path("vehicleSeq").asInt()));
        assertThat(vehiclesOfLine).hasSize(2).allSatisfy((sku, seqs) -> assertThat(seqs).as(sku).hasSize(1));

        // "Vượt sức chứa" with one 2.5 t truck on offer: what fits goes, the rest is left for want of a vehicle.
        UUID one = oneTruck();
        plan = inputs(plan, List.of(shipment), one, "OVER_CAPACITY");
        assertThat(plan.path("status").asText()).as("a new mode: back to DRAFT").isEqualTo("DRAFT");
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("selectionMode").asText()).isEqualTo("OVER_CAPACITY");
        assertThat(plan.path("vehicles")).hasSize(1);
        assertThat(plan.path("unplacedCount").asInt()).isPositive();
        List<String> reasons = new java.util.ArrayList<>();
        plan.path("packages").forEach(p -> {
            if (p.hasNonNull("unplacedReason")) {
                reasons.add(p.path("unplacedReason").asText());
            }
        });
        assertThat(reasons).contains("NO_VEHICLE_AVAILABLE");

        String id = plan.path("loadPlanId").asText();
        dispatch(body(put(PLANS + "/" + id + "/inputs"), "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"shipmentIds\":" + ids(List.of(shipment)) + ",\"vehicleTypeIds\":[\"" + one
                + "\"],\"selectionMode\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("LOAD_PLAN_MODE_INVALID"));
        assertThat(jdbc.queryForObject("SELECT selection_mode FROM fulfillment.load_plan WHERE id = ?::uuid",
                String.class, id)).isEqualTo("OVER_CAPACITY");
    }

    @Test
    @DisplayName("RTM-LPGROUP: \"Ưu tiên + hàng chèn\" - the order line ticked as filler only tops up the vehicle the "
            + "main cargo needs and never opens one; untouched lines are main cargo; a line the plan does not hold is "
            + "refused")
    void priorityFillByOrderLine() throws Exception {
        // Two heavy sofas (2 400 kg) fill a 2.5 t truck but for 100 kg: two of the four tables (38 kg) top it up.
        UUID shipment = packed(order(2, 4));
        String tableSku = sku("TABLE-OAK-160").sku();
        JsonNode candidate = null;
        for (JsonNode c : data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM))
                .andExpect(status().isOk()))) {
            if (c.path("shipmentId").asText().equals(shipment.toString())) {
                candidate = c;
            }
        }
        assertThat(candidate).isNotNull();
        Set<String> tableLines = new HashSet<>();
        candidate.path("packages").forEach(p -> {
            assertThat(p.hasNonNull("orderLineId")).as("a carton holds one order line").isTrue();
            if (p.path("partName").asText().equals(tableSku)) {
                tableLines.add(p.path("orderLineId").asText());
            }
        });
        assertThat(tableLines).hasSize(1);
        String tableLine = tableLines.iterator().next();

        dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"shipmentIds\":" + ids(List.of(shipment)) + ",\"vehicleTypeIds\":[\"" + truck2t5
                + "\"],\"selectionMode\":\"PRIORITY_FILL\",\"fillOrderLineIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("LOAD_PLAN_FILL_LINE_UNKNOWN"));

        JsonNode plan = data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":" + ids(List.of(shipment))
                + ",\"vehicleTypeIds\":[\"" + truck2t5 + "\"],\"selectionMode\":\"PRIORITY_FILL\",\"fillOrderLineIds\":[\""
                + tableLine + "\"]}")).andExpect(status().isCreated()));
        assertThat(plan.path("selectionMode").asText()).isEqualTo("PRIORITY_FILL");
        plan = step(plan, "/calculation", version(plan));

        assertThat(plan.path("vehicles")).as("the filler never opens a vehicle").hasSize(1);
        int fillerPlaced = 0;
        int fillerLeft = 0;
        for (JsonNode p : plan.path("packages")) {
            boolean table = p.path("orderLineId").asText().equals(tableLine);
            assertThat(p.path("fill").asBoolean()).as("fill follows the ticked line").isEqualTo(table);
            if (!table) {
                assertThat(p.hasNonNull("vehicleSeq")).as("main cargo is loaded").isTrue();
            } else if (p.hasNonNull("vehicleSeq")) {
                fillerPlaced++;
            } else {
                fillerLeft++;
            }
        }
        assertThat(fillerPlaced).as("tables top up the truck").isPositive();
        assertThat(fillerLeft).as("the rest waits: it opens no vehicle").isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment.load_plan_package WHERE load_plan_id = ?::uuid"
                + " AND active AND fill", Integer.class, plan.path("loadPlanId").asText())).isEqualTo(4);

        // Without the tick every line is main cargo: the tables need a second truck.
        plan = data(dispatch(body(put(PLANS + "/" + plan.path("loadPlanId").asText() + "/inputs"),
                "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"shipmentIds\":" + ids(List.of(shipment))
                        + ",\"vehicleTypeIds\":[\"" + truck2t5 + "\"],\"fillOrderLineIds\":[]}"))
                .andExpect(status().isOk()));
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("vehicles")).hasSize(2);
        assertThat(plan.path("unplacedCount").asInt()).isZero();
    }

    // ---- 09 §4.2 step 9: one shipment out, only its vehicles recalculated --------------------

    @Test
    @DisplayName("09 §4.2 step 9: a shipment taken out of a reopened plan frees its cartons; only the vehicle that "
            + "shared them waits to be recalculated, the plan stays PLANNED; then the plan confirms - and a cancelled "
            + "booking of its load stays in the load's history (09 §4.2 step 6)")
    void oneShipmentOutThenABookingCancelled() throws Exception {
        UUID twoSofas = packed(order(2, 0));
        UUID oneSofaAndTables = packed(order(1, 2));
        UUID oneSofa = packed(order(1, 0));
        JsonNode plan = create(List.of(twoSofas, oneSofaAndTables, oneSofa), "SINGLE_TYPE");
        plan = step(plan, "/calculation", version(plan));
        plan = step(plan, "/confirmation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
        plan = step(plan, "/reopening", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"reason\":\"Bỏ một lô giao\"}");
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");

        Map<String, Integer> before = seqOf(plan);
        Set<String> leaving = new HashSet<>();
        plan.path("packages").forEach(p -> {
            if (p.path("shipmentId").asText().equals(oneSofa.toString())) {
                leaving.add(p.path("packageId").asText());
            }
        });
        Set<Integer> touched = new HashSet<>();
        leaving.forEach(p -> touched.add(before.get(p)));
        Set<Integer> keptOthers = new HashSet<>();
        before.forEach((pkg, seq) -> {
            if (touched.contains(seq) && !leaving.contains(pkg)) {
                keptOthers.add(seq);
            }
        });

        JsonNode after = step(plan, "/shipments/" + oneSofa + "/removal", version(plan));

        assertThat(after.path("status").asText()).isEqualTo("PLANNED");
        assertThat(after.path("packages").findValuesAsText("shipmentId")).doesNotContain(oneSofa.toString());
        for (JsonNode v : after.path("vehicles")) {
            int seq = v.path("seq").asInt();
            assertThat(v.path("needsRecalculation").asBoolean()).as("vehicle %d", seq)
                    .isEqualTo(keptOthers.contains(seq));
        }
        Map<String, Integer> now = seqOf(after);
        before.forEach((pkg, seq) -> {
            if (!touched.contains(seq)) {
                assertThat(now.get(pkg)).as("an untouched vehicle keeps its layout").isEqualTo(seq);
            }
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fulfillment.load_plan_package
                 WHERE load_plan_id = ?::uuid AND shipment_id = ? AND NOT active AND release_reason = 'INPUTS_CHANGED'""",
                Integer.class, after.path("loadPlanId").asText(), oneSofa)).isEqualTo(leaving.size());
        dispatch(body(post(PLANS + "/" + after.path("loadPlanId").asText() + "/shipments/" + oneSofa + "/removal"),
                version(after))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_SHIPMENT_NOT_IN_PLAN"));

        if (!keptOthers.isEmpty()) {
            dispatch(body(post(PLANS + "/" + after.path("loadPlanId").asText() + "/confirmation"), version(after)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_NEEDS_RECALCULATION"));
            for (int seq : keptOthers) {
                after = step(after, "/vehicles/" + seq + "/recalculation", version(after));
            }
        }
        if (after.path("unplacedCount").asInt() > 0) {
            after = step(after, "/unplaced-acknowledgement", version(after));
        }
        JsonNode confirmed = step(after, "/confirmation", version(after));
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");

        // A booking of its first load, then cancelled: the load is cleared, its history keeps the booking.
        String loadId = confirmed.path("vehicles").get(0).path("load").path("loadId").asText();
        UUID carrier = tx.execute(s -> ReferenceRows.truckCarrier(entityManager, "Xe tải Nhanh"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-123.45\",\"driverName\":\"Lê Văn Lài\",\"driverPhone\":\"0901234567\""
                + ",\"waybillNo\":\"WB-P4-0001\"}")).andExpect(status().isOk());
        dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"Xe không tới\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.waybillNo").doesNotExist());
        Map<String, Object> kept = jdbc.queryForMap("""
                SELECT carrier_id, waybill_no, vehicle_plate, driver_name, driver_phone, booked_at, cancelled_by, reason
                  FROM fulfillment.load_booking_cancellation WHERE load_id = ?::uuid""", loadId);
        assertThat(kept.get("carrier_id")).isEqualTo(carrier);
        assertThat(kept.get("waybill_no")).isEqualTo("WB-P4-0001");
        assertThat(kept.get("vehicle_plate")).isEqualTo("51C-123.45");
        assertThat(kept.get("driver_name")).isEqualTo("Lê Văn Lài");
        assertThat(kept.get("driver_phone")).isEqualTo("0901234567");
        assertThat(kept.get("booked_at")).isNotNull();
        assertThat(kept.get("cancelled_by")).isEqualTo(dispatcher);
        assertThat(kept.get("reason")).isEqualTo("Xe không tới");
    }

    // ---- 09 §4.0: the order estimate -----------------------------------------------------------

    @Test
    @DisplayName("09 §4.0 step 2: a released order's estimate loads the very LPN cartons allocated to it (one box per "
            + "LPN, labelled with it); the estimate takes \"Giữ nguyên dòng\" and \"Ưu tiên + hàng chèn\" by order line")
    void anEstimateOfAllocatedCartons() throws Exception {
        OrderSummary order = order(1, 2);
        Set<String> allocated = new HashSet<>(jdbc.queryForList("""
                SELECT sp.lpn FROM inventory.stock_reservation r
                  JOIN inventory.stock_item si ON si.id = r.stock_item_id
                  JOIN inventory.stock_package sp ON sp.id = si.stock_package_id
                  JOIN ordering.order_line_reservation lr ON lr.reservation_id = r.id
                  JOIN ordering.order_line ol ON ol.id = lr.order_line_id
                 WHERE ol.order_id = ? AND r.status = 'HELD' AND r.stock_item_id IS NOT NULL""", String.class,
                order.orderId()));
        assertThat(allocated).as("released: three whole cartons allocated").hasSize(3);

        JsonNode estimate = data(oc(body(post(BASE + "/load-estimates"), "{\"sources\":[{\"type\":\"ORDER\",\"id\":\""
                + order.orderId() + "\"}],\"vehicleTypeIds\":[\"" + truck2t5 + "\"],\"selectionMode\":\"KEEP_LINES\"}"))
                .andExpect(status().isCreated()));
        assertThat(estimate.path("selectionMode").asText()).isEqualTo("KEEP_LINES");
        assertThat(estimate.path("lines").findValuesAsText("cartons")).containsExactlyInAnyOrder("1", "2");
        Set<String> labels = new HashSet<>(estimate.path("vehicles").findValuesAsText("label"));
        assertThat(labels).isEqualTo(allocated);

        // RTM-LPGROUP: "Ưu tiên + hàng chèn" with the table line ticked; a line the order does not have is refused.
        UUID tableLine = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?",
                UUID.class, order.orderId(), sku("TABLE-OAK-160").sku());
        oc(body(post(BASE + "/load-estimates"), "{\"sources\":[{\"type\":\"ORDER\",\"id\":\"" + order.orderId()
                + "\"}],\"vehicleTypeIds\":[\"" + truck2t5 + "\"],\"selectionMode\":\"PRIORITY_FILL\",\"fillOrderLineIds\":[\""
                + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest());
        JsonNode filled = data(oc(body(post(BASE + "/load-estimates"), "{\"sources\":[{\"type\":\"ORDER\",\"id\":\""
                + order.orderId() + "\"}],\"vehicleTypeIds\":[\"" + truck2t5 + "\"],\"selectionMode\":\"PRIORITY_FILL\","
                + "\"fillOrderLineIds\":[\"" + tableLine + "\"]}")).andExpect(status().isCreated()));
        assertThat(filled.path("selectionMode").asText()).isEqualTo("PRIORITY_FILL");
    }
}
