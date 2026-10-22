package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
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
import org.springframework.context.ApplicationEventPublisher;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Load plans (SCRUM-287 PR D; kltn-docs 09 §4.2, §5, BR-09..BR-15) through the real filter chain, permission aspect,
 * solver and database: candidates, calculation, the FE #71 layout, manual edits checked whole (BR-11, KIT_SPLIT),
 * per-vehicle recalculation, LP-4 and BR-13 at acknowledgement and confirmation, PLANNED dispatch loads, reopening
 * (LP-11) and BR-14, BR-09 under concurrency, an order cancelled out of a plan, permissions and warehouse scope.
 *
 * <p>Its own context, hence its own database: packed orders keep their goods.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.test.context=load-plan"})
class LoadPlanIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final String SOFA_BARCODE = "8930000000011";
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String ORDERS = "/api/v1/orders";
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PLANS = BASE + "/load-plans";
    /** KIT-FORCE: the sofa ships as 2 boxes; packed in an ordinary package, it needs a reason. */
    private static final String ONE_CARTON = "{\"kitOverrideReason\":\"test: packed as one carton\"}";
    /** A sofa carton, and kit part 1 (the sofa itself): 2150 x 950 x 900 mm. */
    private static final String SOFA_MEASURES = "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}";
    /** Kit part 2 (the cushions): 950 x 900 x 400 mm. */
    private static final String PART2_MEASURES = "{\"weightKg\":20,\"lengthCm\":95,\"widthCm\":90,\"heightCm\":40}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID sales;
    private UUID manager;
    private UUID picker;
    private UUID dispatcher;
    private UUID dispatcherElsewhere;
    private UUID customerUser;
    private UUID truck5t;
    private UUID pickup;
    private UUID elsewhere;

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        sales = staff("SALES_STAFF");
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        elsewhere = tx.execute(s -> otherWarehouse());
        dispatcherElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        customerUser = staff("CUSTOMER");
        truck5t = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-5T'", UUID.class);
        pickup = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'PICKUP'", UUID.class);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
        return id;
    }

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

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token(user, role)));
    }

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(dispatcher, "WAREHOUSE_STAFF", request);
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "ORDER_COORDINATOR", request);
    }

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(picker, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private void stock(int sofas) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                    created_at, received_at)
                VALUES (?, ?, 'HCM-A02-1-A', ?, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '400 days')""",
                Identifiers.newId(), SOFA.code(), sofas));
    }

    /** A paid order of the demo customer for {@code sofas} sofas, released at HCM as a full truckload (LP-1). */
    private OrderSummary ftlOrder(int sofas) throws Exception {
        stock(sofas);
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(new PlaceOrderCommand.Line(SOFA, sofas, Money.vnd(12_000_000), null))));
        BigDecimal total = order.total().amount();
        UUID payment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, order.orderId(), total));
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), payment, total, "VND",
                PaymentMethod.BANK_TRANSFER)));
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        oc(body(post(ORDERS + "/" + order.orderId() + "/release"), "{\"warehouseId\":\"" + HCM
                + "\",\"deliveryMode\":\"FULL_TRUCKLOAD\"}")).andExpect(status().isOk());
        return order;
    }

    /** The order picked and packed: as kits of 2 (LP2-6) or one carton per sofa. Returns the shipment and task. */
    private UUID[] packed(OrderSummary order, boolean kits) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"scannedCode\":\"" + line.get("sku")
                            + "\",\"quantity\":" + line.get("allocated_qty") + "}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        int sofas = order.lines().getFirst().quantity();
        UUID line = order.lines().getFirst().lineId();
        for (int i = 0; i < sofas; i++) {
            if (kits) {
                JsonNode kit = data(packer(body(post(BASE + "/tasks/" + task + "/package-kits"), "{\"orderLineId\":\""
                        + line + "\"}")).andExpect(status().isCreated()));
                String part1 = kit.path("packages").get(0).path("packageId").asText();
                String part2 = kit.path("packages").get(1).path("packageId").asText();
                scan(task, part1);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part1 + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part2 + "/seal"), PART2_MEASURES))
                        .andExpect(status().isOk());
            } else {
                String parcel = data(packer(body(post(BASE + "/tasks/" + task + "/packages"), ONE_CARTON))
                        .andExpect(status().isCreated())).path("packageId").asText();
                scan(task, parcel);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
            }
        }
        String shipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk()))
                .path("shipmentId").asText();
        return new UUID[] {UUID.fromString(shipment), task};
    }

    private void scan(UUID task, String parcel) throws Exception {
        packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans"), "{\"scanId\":\""
                + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA_BARCODE + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
    }

    private JsonNode create(String requestBody) throws Exception {
        return data(dispatch(body(post(PLANS), requestBody)).andExpect(status().isCreated()));
    }

    private static String createBody(UUID requestId, List<UUID> shipments, List<UUID> types) {
        return "{\"requestId\":\"" + requestId + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":" + ids(shipments)
                + ",\"vehicleTypeIds\":" + ids(types) + ",\"selectionMode\":\"SINGLE_TYPE\"}";
    }

    private static String ids(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]")
                .orElse("[]");
    }

    private static String version(JsonNode plan) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + "}";
    }

    private static String reason(JsonNode plan, String why) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"reason\":\"" + why + "\"}";
    }

    private JsonNode step(JsonNode plan, String path, String content) throws Exception {
        return data(dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + path), content))
                .andExpect(status().isOk()));
    }

    /** A box of a placements edit: LWH, so w = width, h = height, d = length. */
    private static String box(String ref, int x, int y, int z, int w, int h, int d) {
        return "{\"ref\":\"" + ref + "\",\"xMm\":" + x + ",\"yMm\":" + y + ",\"zMm\":" + z + ",\"wMm\":" + w
                + ",\"hMm\":" + h + ",\"dMm\":" + d + ",\"orientation\":\"LWH\"}";
    }

    private static String edit(JsonNode plan, List<String> boxes, List<String> tray) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"vehicles\":[{\"seq\":1,\"boxes\":["
                + String.join(",", boxes) + "]}],\"tray\":[" + String.join(",", tray.stream().map(t -> "\"" + t + "\"")
                .toList()) + "]}";
    }

    private static Map<String, Integer> codes(JsonNode error) {
        Map<String, Integer> out = new LinkedHashMap<>();
        error.path("fieldErrors").forEach(f -> out.merge(f.path("field").asText() + ":" + f.path("code").asText(), 1,
                Integer::sum));
        return out;
    }

    // ---- the whole flow ----------------------------------------------------------------------

    @Test
    @DisplayName("candidates → plan (idempotent) → calculation → FE #71 layout → manual edits refused (UNSUPPORTED, "
            + "KIT_SPLIT, stale version) and accepted → one vehicle recalculated → LP-4 at acknowledgement and "
            + "confirmation → BR-13 → CONFIRMED with a PLANNED dispatch load, the left-behind parcel free again → "
            + "piece check → reopened (load cancelled, LP-11) → BR-14 once a vehicle loads → cancelled")
    void theWholeFlow() throws Exception {
        OrderSummary orderA = ftlOrder(2);
        OrderSummary orderB = ftlOrder(1);
        UUID shipmentA = packed(orderA, true)[0];
        UUID shipmentB = packed(orderB, false)[0];

        // Candidates: whole full-truckload shipments with their sealed parcels, sizes in mm (LP-5, LP2-1).
        JsonNode candidates = data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM))
                .andExpect(status().isOk()));
        Map<String, JsonNode> byShipment = new LinkedHashMap<>();
        candidates.forEach(c -> byShipment.put(c.path("shipmentId").asText(), c));
        assertThat(byShipment).containsKeys(shipmentA.toString(), shipmentB.toString());
        assertThat(byShipment.get(shipmentA.toString()).path("packageCount").asInt()).isEqualTo(4);
        assertThat(byShipment.get(shipmentA.toString()).path("allowPartialShipment").asBoolean()).isFalse();
        JsonNode sofaCarton = byShipment.get(shipmentB.toString()).path("packages").get(0);
        assertThat(sofaCarton.path("lengthMm").asInt()).isEqualTo(2150);
        assertThat(sofaCarton.path("widthMm").asInt()).isEqualTo(950);
        assertThat(sofaCarton.path("heightMm").asInt()).isEqualTo(900);

        // A plan, idempotent by its request id.
        UUID requestId = UUID.randomUUID();
        JsonNode plan = create(createBody(requestId, List.of(shipmentA, shipmentB), List.of(truck5t)));
        assertThat(plan.path("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.path("code").asText()).startsWith("LP-");
        assertThat(plan.path("packageCount").asInt()).isEqualTo(5);
        assertThat(create(createBody(requestId, List.of(shipmentA, shipmentB), List.of(truck5t)))
                .path("loadPlanId").asText()).isEqualTo(plan.path("loadPlanId").asText());
        String id = plan.path("loadPlanId").asText();

        // Calculation: PLANNED, every parcel placed on one 5 t truck.
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        assertThat(plan.path("vehicles")).hasSize(1);
        assertThat(plan.path("placedCount").asInt()).isEqualTo(5);
        assertThat(plan.path("unplacedCount").asInt()).isZero();
        assertThat(plan.path("solverMeta").path("algorithm").asText()).isNotBlank();

        // The layout, exactly the FE #71 contract.
        JsonNode layout = data(dispatch(get(PLANS + "/" + id + "/vehicles/1/layout")).andExpect(status().isOk()));
        assertThat(layout.path("seq").asInt()).isEqualTo(1);
        assertThat(layout.path("code").asText()).isEqualTo("TRUCK-5T");
        assertThat(layout.path("innerMm").path("l").asInt()).isEqualTo(5200);
        assertThat(layout.path("innerMm").path("w").asInt()).isEqualTo(2050);
        assertThat(layout.path("innerMm").path("h").asInt()).isEqualTo(2050);
        assertThat(layout.path("maxPayloadKg").decimalValue()).isEqualByComparingTo("5000");
        assertThat(layout.path("gussets").isArray()).isTrue();
        assertThat(layout.path("boxes")).hasSize(5);
        assertThat(layout.path("usedWeightKg").decimalValue()).isEqualByComparingTo("197.5");
        assertThat(layout.path("usedVolumeMm3").asLong()).isPositive();
        JsonNode anyBox = layout.path("boxes").get(0);
        assertThat(anyBox.fieldNames()).toIterable().contains("xMm", "yMm", "zMm", "wMm", "hMm", "dMm", "ref",
                "sourceRef", "label", "partName", "kitGroup", "origMm", "orientation", "rotatable", "weightKg");
        assertThat(anyBox.path("sourceRef").isNull()).isTrue();
        assertThat(anyBox.path("label").asText()).startsWith("PKG-");
        assertThat(anyBox.path("orientation").asText()).isIn("LWH", "LHW", "WLH", "WHL", "HLW", "HWL");
        assertThat(data(dispatch(get(PLANS + "/" + id + "/layouts")).andExpect(status().isOk()))).hasSize(1);
        dispatch(get(PLANS + "/" + id + "/vehicles/2/layout")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_VEHICLE_NOT_FOUND"));

        // Which parcel is which: the kits' parts of order A, the carton of order B.
        String carton = null;
        Map<String, List<JsonNode>> kits = new LinkedHashMap<>();
        for (JsonNode p : plan.path("packages")) {
            if (!p.hasNonNull("kitGroupId")) {
                carton = p.path("packageId").asText();
            } else {
                kits.computeIfAbsent(p.path("kitGroupId").asText(), k -> new ArrayList<>()).add(p);
            }
        }
        assertThat(kits).hasSize(2);
        List<String[]> parts = new ArrayList<>();      // [part1, part2] of each kit
        kits.values().forEach(k -> {
            k.sort((a, b) -> a.path("kitPartNo").asInt() - b.path("kitPartNo").asInt());
            parts.add(new String[] {k.get(0).path("packageId").asText(), k.get(1).path("packageId").asText()});
        });
        assertThat(plan.path("packages").findValuesAsText("partName")).contains("SOFA-3S-GREY 1/2",
                "SOFA-3S-GREY 2/2", "SOFA-3S-GREY");

        // A known floor layout: two sofas side by side, the carton behind, the cushions beside it.
        String sofa1 = box(parts.get(0)[0], 0, 0, 0, 950, 900, 2150);
        String sofa2 = box(parts.get(1)[0], 950, 0, 0, 950, 900, 2150);
        String cartonOnFloor = box(carton, 0, 0, 2150, 950, 900, 2150);
        String cushions1 = box(parts.get(0)[1], 950, 0, 2150, 900, 400, 950);
        String cushions2 = box(parts.get(1)[1], 950, 0, 3100, 900, 400, 950);
        String cushions1Floating = box(parts.get(0)[1], 950, 10, 2150, 900, 400, 950);

        // BR-11: a box 10 mm in the air is UNSUPPORTED - refused, nothing changed.
        JsonNode refused = body(dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan,
                List.of(sofa1, sofa2, cartonOnFloor, cushions1Floating, cushions2), List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PLACEMENT_RULE_VIOLATION")));
        assertThat(codes(refused)).containsKey(parts.get(0)[1] + ":UNSUPPORTED");
        // LP-3: a kit's cushions on the tray without their sofa is KIT_SPLIT, for both parts.
        refused = body(dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan,
                List.of(sofa1, sofa2, cartonOnFloor, cushions2), List.of(parts.get(0)[1]))))
                .andExpect(status().isUnprocessableEntity()));
        assertThat(codes(refused)).containsOnlyKeys(parts.get(0)[0] + ":KIT_SPLIT", parts.get(0)[1] + ":KIT_SPLIT");
        // Every package exactly once.
        dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan, List.of(sofa1, sofa2), List.of())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(data(dispatch(get(PLANS + "/" + id)).andExpect(status().isOk())).path("version").asLong())
                .as("nothing changed").isEqualTo(plan.path("version").asLong());

        // A valid edit: the carton of order B goes to the tray (MANUAL).
        JsonNode edited = data(dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan,
                List.of(sofa1, sofa2, cushions1, cushions2), List.of(carton)))).andExpect(status().isOk()));
        assertThat(edited.path("version").asLong()).isGreaterThan(plan.path("version").asLong());
        assertThat(edited.path("unplacedCount").asInt()).isEqualTo(1);
        String cartonId = carton;
        assertThat(edited.path("packages")).filteredOn(p -> p.path("packageId").asText().equals(cartonId))
                .singleElement().satisfies(p -> {
                    assertThat(p.path("unplacedReason").asText()).isEqualTo("MANUAL");
                    assertThat(p.path("vehicleSeq").isNumber()).isFalse();
                });
        // A stale version is refused.
        dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan, List.of(sofa1, sofa2, cushions1, cushions2,
                cartonOnFloor), List.of()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        plan = edited;

        // One vehicle recalculated with the carton taken back from the tray.
        plan = step(plan, "/vehicles/1/recalculation", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"addPackageIds\":[\"" + carton + "\"]}");
        assertThat(plan.path("unplacedCount").asInt()).isZero();
        assertThat(plan.path("placedCount").asInt()).isEqualTo(5);

        // Back to the floor layout with the carton on the tray.
        plan = data(dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan,
                List.of(sofa1, sofa2, cushions1, cushions2), List.of(carton)))).andExpect(status().isOk()));

        // LP-4: order B does not allow partial shipments - acknowledging or confirming its left-behind carton is 409.
        dispatch(body(post(PLANS + "/" + id + "/unplaced-acknowledgement"), version(plan)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(orderB.orderNumber()));
        dispatch(body(post(PLANS + "/" + id + "/confirmation"), version(plan)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED"));

        // Sales allows it; BR-13: still to be acknowledged first.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", orderB.orderId()));
        dispatch(body(post(PLANS + "/" + id + "/confirmation"), version(plan)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED"));
        plan = step(plan, "/unplaced-acknowledgement", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"note\":\"carton goes on the next truck\"}");
        assertThat(plan.path("unplacedAcknowledgement").path("note").asText()).isEqualTo("carton goes on the next truck");

        // Confirmed: one PLANNED full-truckload dispatch load for the vehicle; the carton is free again.
        plan = step(plan, "/confirmation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(plan.path("packageCount").asInt()).isEqualTo(4);
        assertThat(plan.path("vehicles").get(0).path("load").path("status").asText()).isEqualTo("PLANNED");
        UUID vehicleId = UUID.fromString(plan.path("vehicles").get(0).path("loadPlanVehicleId").asText());
        Map<String, Object> load = jdbc.queryForMap("SELECT id, mode, status, carrier_id, code FROM "
                + "fulfillment.dispatch_load WHERE load_plan_vehicle_id = ?", vehicleId);
        assertThat(load).containsEntry("mode", "FULL_TRUCKLOAD").containsEntry("status", "PLANNED");
        assertThat(load.get("carrier_id")).isNull();
        assertThat((String) load.get("code")).startsWith("LD-");
        assertThat(jdbc.queryForObject("SELECT release_reason FROM fulfillment.load_plan_package WHERE package_id = "
                + "?::uuid", String.class, carton)).isEqualTo("LEFT_BEHIND");
        JsonNode again = data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()));
        assertThat(again).filteredOn(c -> c.path("shipmentId").asText().equals(shipmentB.toString())).singleElement()
                .satisfies(c -> assertThat(c.path("packageCount").asInt()).isEqualTo(1));
        assertThat(again).noneMatch(c -> c.path("shipmentId").asText().equals(shipmentA.toString()));
        // BR-14: locked.
        dispatch(body(put(PLANS + "/" + id + "/placements"), edit(plan, List.of(sofa1, sofa2, cushions1, cushions2),
                List.of()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_PLAN_TRANSITION"));

        // The piece check: in = placed + unplaced, per shipment and order.
        JsonNode check = data(dispatch(get(PLANS + "/" + id + "/reconciliation")).andExpect(status().isOk()));
        assertThat(check.path("pieceCheck").path("packagesIn").asInt()).isEqualTo(5);
        assertThat(check.path("pieceCheck").path("placed").asInt()).isEqualTo(4);
        assertThat(check.path("pieceCheck").path("unplaced").asInt()).isEqualTo(1);
        assertThat(check.path("pieceCheck").path("leftBehind").asInt()).isEqualTo(1);
        assertThat(check.path("pieceCheck").path("balanced").asBoolean()).isTrue();
        assertThat(check.path("shipments")).hasSize(2);
        assertThat(check.path("orders")).filteredOn(o -> o.path("code").asText().equals(orderB.orderNumber()))
                .singleElement().satisfies(o -> {
                    assertThat(o.path("leftBehind").asInt()).isEqualTo(1);
                    assertThat(o.path("placed").asInt()).isZero();
                });
        assertThat(check.path("vsEstimate").isNull() || check.path("vsEstimate").isMissingNode()).isTrue();

        // Reopened (LP-11): the PLANNED load is cancelled with the reason.
        dispatch(body(post(PLANS + "/" + id + "/reopening"), "{\"expectedVersion\":" + plan.path("version").asLong()
                + "}")).andExpect(status().isBadRequest());
        plan = step(plan, "/reopening", reason(plan, "the 5 t truck is not available"));
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        assertThat(jdbc.queryForMap("SELECT status, cancel_reason FROM fulfillment.dispatch_load WHERE id = ?",
                load.get("id"))).containsEntry("status", "CANCELLED")
                .satisfies(row -> assertThat((String) row.get("cancel_reason")).contains("the 5 t truck is not available"));

        // Confirmed again; once its vehicle is loading (PR E), neither reopened nor cancelled (BR-14).
        plan = step(plan, "/confirmation", version(plan));
        UUID loadId = jdbc.queryForObject("SELECT id FROM fulfillment.dispatch_load WHERE load_plan_vehicle_id = ? AND "
                + "status = 'PLANNED'", UUID.class, vehicleId);
        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.dispatch_load SET status = 'LOADING', carrier_id = ?, "
                + "loading_started_at = NOW() WHERE id = ?", ghn, loadId));
        dispatch(body(post(PLANS + "/" + id + "/reopening"), reason(plan, "x"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_LOADING_STARTED"));
        dispatch(body(post(PLANS + "/" + id + "/cancellation"), reason(plan, "x"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_LOADING_STARTED"));

        // Not loading after all: cancelled - the parcels are free, the load cancelled.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.dispatch_load SET status = 'PLANNED', "
                + "loading_started_at = NULL, carrier_id = NULL WHERE id = ?", loadId));
        plan = step(plan, "/cancellation", reason(plan, "customer will collect"));
        assertThat(plan.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.dispatch_load WHERE id = ?", String.class,
                loadId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_plan_package WHERE load_plan_id = "
                + "?::uuid AND active", Integer.class, id)).isZero();
        JsonNode free = data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()));
        assertThat(free).filteredOn(c -> c.path("shipmentId").asText().equals(shipmentA.toString())).singleElement()
                .satisfies(c -> assertThat(c.path("packageCount").asInt()).isEqualTo(4));
        assertThat(data(dispatch(get(PLANS + "?warehouseId=" + HCM + "&status=CANCELLED")).andExpect(status().isOk()))
                .path("items").findValuesAsText("loadPlanId")).contains(id);
    }

    // ---- BR-09 -------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-09: two plans claiming one shipment at once - exactly one wins, the other is 409 "
            + "LOAD_PLAN_PACKAGE_TAKEN; the database refuses a second live row too; cancelling frees the parcel")
    void oneParcelOneLivePlan() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode first = create(createBody(UUID.randomUUID(), List.of(), List.of(truck5t)));
        JsonNode second = create(createBody(UUID.randomUUID(), List.of(), List.of(truck5t)));
        String input = ",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\"" + truck5t + "\"]}";
        List<String> tokens = List.of(token(dispatcher, "WAREHOUSE_STAFF"), token(manager, "WAREHOUSE_MANAGER"));
        List<JsonNode> plans = List.of(first, second);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            Callable<Integer> claim = () -> {
                go.await();
                return mvc.perform(put(PLANS + "/" + plans.get(n).path("loadPlanId").asText() + "/inputs")
                        .header("Authorization", tokens.get(n)).contentType("application/json")
                        .content("{\"expectedVersion\":" + plans.get(n).path("version").asLong() + input))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(claim));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_plan_package lp JOIN fulfillment.package p "
                + "ON p.id = lp.package_id WHERE p.shipment_id = ? AND lp.active", Integer.class, shipment)).isEqualTo(1);

        JsonNode winner = statuses.get(0) == 200 ? first : second;
        JsonNode loser = winner == first ? second : first;
        winner = data(dispatch(get(PLANS + "/" + winner.path("loadPlanId").asText())).andExpect(status().isOk()));
        loser = data(dispatch(get(PLANS + "/" + loser.path("loadPlanId").asText())).andExpect(status().isOk()));
        dispatch(body(put(PLANS + "/" + loser.path("loadPlanId").asText() + "/inputs"), "{\"expectedVersion\":"
                + loser.path("version").asLong() + input)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_PACKAGE_TAKEN"));

        // The partial unique index is the net under the service.
        UUID packageId = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE shipment_id = ?", UUID.class,
                shipment);
        String loserId = loser.path("loadPlanId").asText();
        Throwable byHand = catchThrowable(() -> jdbc.update("""
                INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, shipment_id, active, added_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, TRUE, NOW())""", loserId, packageId, shipment));
        assertThat(byHand).hasMessageContaining("uk_load_plan_package_active");

        step(winner, "/cancellation", reason(winner, "plan merged"));
        JsonNode taken = data(dispatch(body(put(PLANS + "/" + loserId + "/inputs"), "{\"expectedVersion\":"
                + loser.path("version").asLong() + input)).andExpect(status().isOk()));
        assertThat(taken.path("packageCount").asInt()).isEqualTo(1);
    }

    // ---- order cancellation, frozen parcels -------------------------------------------------

    @Test
    @DisplayName("a parcel in a plan is frozen (409 PACKAGE_IN_LOAD_PLAN; the trigger refuses it too); its order "
            + "cancelled out of a confirmed plan: the parcel leaves the plan, the plan goes back to PLANNED and its "
            + "PLANNED load is cancelled, the parcel is voided and the shipment cancelled")
    void anOrderCancelledOutOfAConfirmedPlan() throws Exception {
        OrderSummary cancelled = ftlOrder(1);
        UUID[] doomed = packed(cancelled, false);
        UUID[] kept = packed(ftlOrder(1), false);
        JsonNode plan = create(createBody(UUID.randomUUID(), List.of(doomed[0], kept[0]), List.of(truck5t)));
        plan = step(plan, "/calculation", version(plan));
        plan = step(plan, "/confirmation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
        String id = plan.path("loadPlanId").asText();
        UUID parcel = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE shipment_id = ?", UUID.class,
                doomed[0]);
        UUID loadId = jdbc.queryForObject("SELECT l.id FROM fulfillment.dispatch_load l JOIN fulfillment.load_plan_vehicle v "
                + "ON v.id = l.load_plan_vehicle_id WHERE v.load_plan_id = ?::uuid", UUID.class, id);

        packer(delete(BASE + "/tasks/" + doomed[1] + "/packages/" + parcel)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_IN_LOAD_PLAN"));
        packer(post(BASE + "/tasks/" + doomed[1] + "/packages/" + parcel + "/reopening")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_IN_LOAD_PLAN"));
        Throwable byHand = catchThrowable(() -> jdbc.update(
                "UPDATE fulfillment.package SET height_cm = 10 WHERE id = ?", parcel));
        assertThat(byHand).hasMessageContaining("tg_package_in_load_plan");

        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(cancelled.orderId(),
                cancelled.orderNumber(), DemoData.CUSTOMER_ID, "PACKED", "OTHER", "test", BigDecimal.ZERO,
                BigDecimal.ZERO, "VND", coordinator, Instant.now())));
        Await.until("the order's parcel leaves the plan", ASYNC, () -> "ORDER_CANCELLED".equals(jdbc.queryForObject(
                "SELECT release_reason FROM fulfillment.load_plan_package WHERE package_id = ?", String.class, parcel)));
        JsonNode after = data(dispatch(get(PLANS + "/" + id)).andExpect(status().isOk()));
        assertThat(after.path("status").asText()).isEqualTo("PLANNED");
        assertThat(after.path("packageCount").asInt()).isEqualTo(1);
        assertThat(after.path("placedCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, cancel_reason FROM fulfillment.dispatch_load WHERE id = ?", loadId))
                .containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.package WHERE id = ?", String.class, parcel))
                .isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, doomed[0]))
                .isEqualTo("CANCELLED");
        // The plan is consistent: confirmed again with what is left.
        assertThat(step(after, "/confirmation", version(after)).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("what fits no vehicle type is unplaced with its reason (a sofa on a pickup is TOO_LARGE); a PLANNED "
            + "plan whose inputs change goes back to DRAFT with no vehicle")
    void unplacedReasonsAndInputs() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = create(createBody(UUID.randomUUID(), List.of(shipment), List.of(pickup)));
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        assertThat(plan.path("vehicles")).isEmpty();
        assertThat(plan.path("packages").get(0).path("unplacedReason").asText()).isEqualTo("TOO_LARGE");
        dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + "/confirmation"), version(plan)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_EMPTY"));

        plan = data(dispatch(body(put(PLANS + "/" + plan.path("loadPlanId").asText() + "/inputs"), "{\"expectedVersion\":"
                + plan.path("version").asLong() + ",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\""
                + truck5t + "\"],\"selectionMode\":\"FEWEST_VEHICLES\"}")).andExpect(status().isOk()));
        assertThat(plan.path("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.path("selectionMode").asText()).isEqualTo("FEWEST_VEHICLES");
        assertThat(plan.path("vehicles")).isEmpty();
        assertThat(plan.path("packages").get(0).path("unplacedReason").isTextual()).as("cleared").isFalse();
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("placedCount").asInt()).isEqualTo(1);
    }

    // ---- who may -----------------------------------------------------------------------------

    @Test
    @DisplayName("permissions and warehouse scope: the coordinator reads but does not plan (403); Sales and a customer "
            + "403; warehouse staff of another warehouse get OUT_OF_DATA_SCOPE for this warehouse's plans and candidates")
    void whoMay() throws Exception {
        JsonNode plan = create(createBody(UUID.randomUUID(), List.of(), List.of(truck5t)));
        String url = PLANS + "/" + plan.path("loadPlanId").asText();
        oc(get(url)).andExpect(status().isOk());
        oc(get(PLANS)).andExpect(status().isOk());
        oc(body(post(PLANS), createBody(UUID.randomUUID(), List.of(), List.of()))).andExpect(status().isForbidden());
        oc(body(post(url + "/calculation"), version(plan))).andExpect(status().isForbidden());
        as(sales, "SALES_STAFF", get(url)).andExpect(status().isForbidden());
        as(customerUser, "CUSTOMER", get(PLANS)).andExpect(status().isForbidden());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(url)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(url + "/vehicles/1/layout")).andExpect(status().isForbidden());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", body(post(url + "/cancellation"), reason(plan, "x")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(BASE + "/load-plan-candidates?warehouseId=" + HCM))
                .andExpect(status().isForbidden());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", body(post(PLANS), createBody(UUID.randomUUID(), List.of(),
                List.of()))).andExpect(status().isForbidden());
        assertThat(data(as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(PLANS)).andExpect(status().isOk()))
                .path("items").findValuesAsText("loadPlanId")).doesNotContain(plan.path("loadPlanId").asText());
        dispatch(get(PLANS + "/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_NOT_FOUND"));
        as(manager, "WAREHOUSE_MANAGER", body(post(url + "/cancellation"), reason(plan, "not needed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
