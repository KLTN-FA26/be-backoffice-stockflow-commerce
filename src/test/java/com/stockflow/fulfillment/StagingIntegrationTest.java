package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team plan 4C-5 through the real filter chain, permission aspect, warehouse scope and database: a task whose
 * destination is STAGING moves each confirmed carton to the staging area and keeps its allocation held; the warehouse
 * manager moves the whole task to dispatch, which deducts every carton where it stands; an order cancelled meanwhile
 * has its cartons moved back onto a bin, never deducted; stock standing in a staging area is out of ATP and is not
 * moved by hand; the destination and the assignee change only while the task is PENDING; a top-up adds lines to the
 * same task.
 *
 * <p>Like {@link PickConfirmationIntegrationTest}, each test brings sofa cartons of its own, received long before the
 * demo ones, which the allocator hands out first, so the shared demo cartons are not used up. HCM has exactly one
 * staging area in the demo data (HCM-STG01), so a STAGING task needs no area named.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class StagingIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(10);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String SOFA_BIN = "HCM-A01-2-B";
    private static final String OTHER_SOFA_BIN = "HCM-A02-1-A";
    private static final String STAGING = "HCM-STG01";

    @Autowired com.stockflow.common.security.RoleAuthorizationLookup grants;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    @Autowired InventoryService inventory;
    @Autowired PickLists pickLists;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID managerHcm;
    private UUID pickerHcm;
    private UUID otherPickerHcm;
    private final List<UUID> placed = new ArrayList<>();
    private final List<String> extraAreas = new ArrayList<>();
    private final List<UUID> extraWarehouses = new ArrayList<>();

    private Set<com.stockflow.common.security.PermissionCode> seeded(Role role) {
        return grants.resolve(List.of(role.authority())).permissions();
    }

    @BeforeEach
    void setUp() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));
        tx.executeWithoutResult(s -> {
            CartonRows.stock(jdbc, SOFA.code(), SOFA_BIN, 3, 1, null, longAgo);
            CartonRows.stock(jdbc, SOFA.code(), OTHER_SOFA_BIN, 3, 1, null, longAgo);
        });
        coordinator = staff("ORDER_COORDINATOR");
        managerHcm = staff("WAREHOUSE_MANAGER", HCM);
        pickerHcm = staff("WAREHOUSE_STAFF", HCM);
        otherPickerHcm = staff("WAREHOUSE_STAFF", HCM);
    }

    /** Cancels what the test placed, puts every carton a task still has out of its bin back, drops extra areas. */
    @AfterEach
    void cleanUp() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus current = orders.findById(id).orElseThrow().status();
            if (current == OrderStatus.ON_HOLD) {
                orders.resolveHold(id, "TEST_CLEANUP", managerHcm);
            }
            if (orders.findById(id).orElseThrow().status() != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
        CurrentUser system = new CurrentUser(managerHcm, "cleanup", Set.of(Role.WAREHOUSE_MANAGER),
                seeded(Role.WAREHOUSE_MANAGER), DataScope.ALL, Set.of());
        for (UUID order : placed) {
            List<Map<String, Object>> out = jdbc.queryForList("""
                    SELECT l.id, l.pick_id, l.location_code, l.lpn FROM fulfillment.pick_line l
                      JOIN fulfillment.pick p ON p.id = l.pick_id
                     WHERE p.order_id = ? AND l.status IN ('PICKED', 'STAGED')""", order);
            if (out.isEmpty()) {
                continue;
            }
            Await.until("task of the cancelled order cancelled", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                    "SELECT status FROM fulfillment.pick WHERE order_id = ?", String.class, order)));
            for (Map<String, Object> line : out) {
                pickLists.putBack((UUID) line.get("pick_id"), (UUID) line.get("id"),
                        (String) line.get("location_code"), (String) line.get("lpn"), system);
            }
        }
        // The asynchronous listeners of what the test did (cancellations, mails) finish before the next test class runs:
        // statement counters of other classes are shared by every thread.
        for (UUID order : placed) {
            Await.until("events of the order handled", ASYNC, () -> jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?",
                    Integer.class, "%" + order + "%") == 0);
        }
        for (String code : extraAreas) {
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM warehouse.area WHERE location_id = (SELECT id FROM warehouse.storage_location "
                        + "WHERE location_code = ?)", code);
                jdbc.update("DELETE FROM warehouse.storage_location WHERE location_code = ?", code);
            });
        }
        for (UUID warehouse : extraWarehouses) {
            tx.executeWithoutResult(s -> jdbc.update("DELETE FROM warehouse.warehouse WHERE id = ?", warehouse));
        }
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

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private String oc() {
        return token(coordinator, "ORDER_COORDINATOR");
    }

    private String manager() {
        return token(managerHcm, "WAREHOUSE_MANAGER");
    }

    private String picker() {
        return token(pickerHcm, "WAREHOUSE_STAFF");
    }

    /** A paid stock-only order of the demo customer, its stock held at HCM, released there (READY_TO_FULFILL). */
    private OrderSummary confirmedOrder(int sofas) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(new PlaceOrderCommand.Line(SOFA, sofas, Money.vnd(12_000_000), null))));
        placed.add(order.orderId());
        BigDecimal total = order.total().amount();
        UUID payment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, order.orderId(), total));
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), payment, total, "VND",
                PaymentMethod.BANK_TRANSFER)));
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        return releases.release(order.orderId(), HCM, coordinator);
    }

    private ResultActions call(RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** OC opens the task for {@link #pickerHcm}; {@code destinationBody} is the JSON fragment after the assignee. */
    private UUID createTask(OrderSummary order, String destinationBody) throws Exception {
        String body = "{\"orderId\":\"" + order.orderId() + "\",\"assignedUserId\":\"" + pickerHcm + "\""
                + destinationBody + "}";
        return UUID.fromString(body(call(post("/api/v1/fulfillment/tasks").header("Authorization", oc())
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn()).path("taskId").asText());
    }

    private UUID stagingTask(OrderSummary order) throws Exception {
        UUID task = createTask(order, ",\"destination\":\"STAGING\"");
        call(get("/api/v1/fulfillment/tasks/" + task).header("Authorization", oc()))
                .andExpect(jsonPath("$.data.destination").value("STAGING"))
                .andExpect(jsonPath("$.data.stagingLocationCode").value(STAGING));
        return task;
    }

    private void start(UUID task) throws Exception {
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start").header("Authorization", picker()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKING"));
    }

    private JsonNode lines(UUID task) throws Exception {
        return body(call(get("/api/v1/fulfillment/tasks/" + task + "/pick-lines").header("Authorization", picker()))
                .andExpect(status().isOk()).andReturn()).path("items");
    }

    private ResultActions confirm(UUID task, JsonNode line) throws Exception {
        return call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + line.path("lineId").asText()
                + "/confirmation").header("Authorization", picker()).contentType("application/json")
                .content("{\"locationCode\":\"" + line.path("locationCode").asText() + "\",\"lpn\":\""
                        + line.path("lpn").asText() + "\"}"));
    }

    private void confirmAll(UUID task) throws Exception {
        for (JsonNode line : lines(task)) {
            if ("OPEN".equals(line.path("status").asText())) {
                confirm(task, line).andExpect(status().isOk());
            }
        }
    }

    private ResultActions complete(UUID task) throws Exception {
        return call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion").header("Authorization", picker()));
    }

    private ResultActions dispatchTransfer(UUID task) throws Exception {
        return call(post("/api/v1/fulfillment/tasks/" + task + "/dispatch-transfer").header("Authorization", manager()));
    }

    private String pickStatus(UUID task) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task);
    }

    private String locationOf(String lpn) {
        return jdbc.queryForObject("SELECT location_code FROM inventory.stock_package WHERE lpn = ?", String.class, lpn);
    }

    private String rowLocationOf(String lpn) {
        return jdbc.queryForObject("""
                SELECT s.location_code FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
                 WHERE p.lpn = ?""", String.class, lpn);
    }

    private int onHandOf(String lpn) {
        return jdbc.queryForObject("""
                SELECT s.on_hand FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
                 WHERE p.lpn = ?""", Integer.class, lpn);
    }

    private String cartonStatus(String lpn) {
        return jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn);
    }

    private String holdStatusOf(JsonNode line) {
        return jdbc.queryForObject("""
                SELECT r.status FROM inventory.stock_reservation r JOIN fulfillment.pick_line l ON l.reservation_id = r.id
                 WHERE l.id = ?::uuid""", String.class, line.path("lineId").asText());
    }

    private int ledgerRows(String type, String lineId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement
                 WHERE movement_type = ? AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""",
                Integer.class, type, lineId);
    }

    private int atp() {
        return inventory.availableToPromise(SOFA);
    }

    // ---- picking to the staging area -----------------------------------------------------------

    @Test
    @DisplayName("a staged line moves its carton whole to HCM-STG01 and keeps the hold: on_hand unchanged, a MOVE row, no PICK; "
            + "the task completes STAGED, the order stays PICKING and packing refuses it")
    void pickingToStagingMovesTheCartonAndKeepsTheHold() throws Exception {
        OrderSummary order = confirmedOrder(2);
        UUID task = stagingTask(order);
        start(task);
        int atpBefore = atp();
        JsonNode first = lines(task).get(0);
        String lpn = first.path("lpn").asText();
        String lineId = first.path("lineId").asText();
        String bin = first.path("locationCode").asText();

        confirm(task, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STAGED"))
                .andExpect(jsonPath("$.data.stagedLocationCode").value(STAGING))
                .andExpect(jsonPath("$.data.pickedQty").value(1));

        assertThat(locationOf(lpn)).as("the carton moved whole").isEqualTo(STAGING);
        assertThat(rowLocationOf(lpn)).as("with its row").isEqualTo(STAGING);
        assertThat(cartonStatus(lpn)).isEqualTo("IN_STOCK");
        assertThat(onHandOf(lpn)).as("nothing deducted").isEqualTo(1);
        assertThat(holdStatusOf(first)).as("the order still owns it").isEqualTo("HELD");
        assertThat(ledgerRows("MOVE", lineId)).isEqualTo(1);
        assertThat(ledgerRows("PICK", lineId)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT from_location_code, to_location_code FROM inventory.stock_movement
                 WHERE movement_type = 'MOVE' AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""", lineId))
                .containsEntry("from_location_code", bin).containsEntry("to_location_code", STAGING);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM fulfillment.pick_line WHERE id = ?::uuid",
                Instant.class, lineId)).isNull();
        assertThat(atp()).as("stock in a staging area is not in ATP; it was held already").isEqualTo(atpBefore);
        assertThat(inventory.availabilityOf(SOFA)).extracting(a -> a.locationCode()).doesNotContain(STAGING);

        // the same scan again: 200, one ledger row
        confirm(task, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("STAGED"));
        assertThat(ledgerRows("MOVE", lineId)).isEqualTo(1);

        // the task is not complete with a line still open
        complete(task).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LINES_INCOMPLETE"));
        confirm(task, lines(task).get(1)).andExpect(status().isOk());
        complete(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("STAGED"))
                .andExpect(jsonPath("$.data.stagedAt").isNotEmpty());
        complete(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("STAGED"));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
        assertThat(atp()).isEqualTo(atpBefore);

        // packing works on a PICKED task only
        call(post("/api/v1/fulfillment/tasks/" + task + "/packing-completion").header("Authorization", picker()))
                .andExpect(status().is4xxClientError());
        assertThat(pickStatus(task)).isEqualTo("STAGED");
    }

    // ---- moving the whole task to dispatch -----------------------------------------------------

    @Test
    @DisplayName("dispatch-transfer deducts every staged line where it stands and the task becomes PICKED; refused while the "
            + "order is ON_HOLD; a repeat answers the task")
    void movingTheTaskToDispatchDeductsEveryLine() throws Exception {
        OrderSummary order = confirmedOrder(2);
        UUID task = stagingTask(order);
        start(task);
        confirmAll(task);
        complete(task).andExpect(status().isOk());
        JsonNode lines = lines(task);
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line.path("status").asText()).isEqualTo("STAGED"));
        String firstLpn = lines.get(0).path("lpn").asText();

        // not yet staged: a PENDING task is refused too
        UUID other = createTask(confirmedOrder(1), "");
        dispatchTransfer(other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));

        // the picker is not the warehouse manager
        call(post("/api/v1/fulfillment/tasks/" + task + "/dispatch-transfer").header("Authorization", picker()))
                .andExpect(status().isForbidden());

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.putOnHold(order.orderId(), HoldReason.OTHER, "waiting for the customer", managerHcm));
        dispatchTransfer(task).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_ORDER_ON_HOLD"));
        assertThat(pickStatus(task)).as("the cartons stay where they are").isEqualTo("STAGED");
        assertThat(locationOf(firstLpn)).isEqualTo(STAGING);
        assertThat(onHandOf(firstLpn)).isEqualTo(1);
        assertThat(ledgerRows("PICK", lines.get(0).path("lineId").asText())).isZero();

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.resolveHold(order.orderId(), "resolved", managerHcm));
        dispatchTransfer(task).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"))
                .andExpect(jsonPath("$.data.destination").value("STAGING"))
                .andExpect(jsonPath("$.data.movedToDispatchAt").isNotEmpty());

        for (JsonNode line : lines) {
            String lineId = line.path("lineId").asText();
            String lpn = line.path("lpn").asText();
            assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                    lineId)).isEqualTo("PICKED");
            assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM fulfillment.pick_line WHERE id = ?::uuid",
                    Boolean.class, lineId)).isTrue();
            assertThat(holdStatusOf(line)).isEqualTo("CONSUMED");
            assertThat(cartonStatus(lpn)).isEqualTo("CONSUMED");
            assertThat(onHandOf(lpn)).isZero();
            assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT from_location_code FROM inventory.stock_movement
                     WHERE movement_type = 'PICK' AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""",
                    String.class, lineId)).as("deducted from the staging area").isEqualTo(STAGING);
        }
        assertThat(jdbc.queryForObject("SELECT moved_to_dispatch_by FROM fulfillment.pick WHERE id = ?", UUID.class,
                task)).isEqualTo(managerHcm);

        // again: 200 with the task as it is, nothing deducted twice
        dispatchTransfer(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'PICK'
                   AND reference_type = 'PICK_LINE' AND reference_id IN (SELECT id FROM fulfillment.pick_line WHERE pick_id = ?)""",
                Integer.class, task)).isEqualTo(2);
        // the packing queue now has it: a PICKED task has a pack to work on
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pack WHERE pick_id = ? AND status = 'PENDING'",
                Integer.class, task)).isEqualTo(1);
    }

    // ---- cancelling while staged ---------------------------------------------------------------

    @Test
    @DisplayName("an order cancelled while its task is STAGED: the put-back queue shows the cartons at the staging area, ATP "
            + "rises only when each is put back onto a bin, never deducted; the put-back is idempotent")
    void aCancelledOrdersStagedCartonsGoBackToABin() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID task = stagingTask(order);
        start(task);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String lpn = line.path("lpn").asText();
        String bin = line.path("locationCode").asText();
        confirm(task, line).andExpect(status().isOk());
        complete(task).andExpect(status().isOk());
        int atpStaged = atp();

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(pickStatus(task)));
        assertThat(jdbc.queryForObject("SELECT needs_put_back FROM fulfillment.pick WHERE id = ?", Boolean.class, task))
                .isTrue();
        assertThat(holdStatusOf(line)).as("the hold was released").isEqualTo("RELEASED");
        assertThat(locationOf(lpn)).as("the carton did not move by itself").isEqualTo(STAGING);
        assertThat(atp()).as("a released carton in a staging area is not promised").isEqualTo(atpStaged);

        call(get("/api/v1/fulfillment/put-backs").param("warehouseId", HCM.toString()).param("size", "200")
                .header("Authorization", picker()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].lineId", hasItem(lineId)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].fromLocationCode")
                        .value(org.hamcrest.Matchers.contains(STAGING)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].lpn")
                        .value(org.hamcrest.Matchers.contains(lpn)));

        String url = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back";
        String requestBody = "{\"locationCode\":\"" + bin + "\",\"lpn\":\"" + lpn + "\"}";
        // onto a staging area: refused, nothing moves
        call(post(url).header("Authorization", picker()).contentType("application/json")
                .content("{\"locationCode\":\"" + STAGING + "\",\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(locationOf(lpn)).isEqualTo(STAGING);

        call(post(url).header("Authorization", picker()).contentType("application/json").content(requestBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(locationOf(lpn)).isEqualTo(bin);
        assertThat(rowLocationOf(lpn)).isEqualTo(bin);
        assertThat(cartonStatus(lpn)).isEqualTo("IN_STOCK");
        assertThat(onHandOf(lpn)).isEqualTo(1);
        assertThat(ledgerRows("MOVE", lineId)).as("staged out of the bin, then back out of staging").isEqualTo(2);
        assertThat(ledgerRows("PICK", lineId)).as("never deducted").isZero();
        assertThat(ledgerRows("PUT_BACK", lineId)).isZero();
        assertThat(atp()).as("the carton is promisable again").isEqualTo(atpStaged + 1);

        call(post(url).header("Authorization", picker()).contentType("application/json").content(requestBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(ledgerRows("MOVE", lineId)).as("the repeat wrote nothing").isEqualTo(2);
        assertThat(atp()).isEqualTo(atpStaged + 1);
    }

    // ---- a top-up ------------------------------------------------------------------------------

    @Test
    @DisplayName("a top-up adds a line to the same STAGED task for a hold it never had: the task goes back to PICKING, the new "
            + "carton is staged too, and the task is STAGED again with both")
    void aTopUpAddsLinesToTheSameTask() throws Exception {
        OrderSummary order = confirmedOrder(2);
        UUID task = stagingTask(order);
        start(task);
        // The second hold is one the task has no line for, as if it had been allocated after the task was drawn up.
        List<JsonNode> planned = new ArrayList<>();
        lines(task).forEach(planned::add);
        assertThat(planned).hasSize(2);
        String laterLine = planned.get(1).path("lineId").asText();
        String laterLpn = planned.get(1).path("lpn").asText();
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM fulfillment.pick_line WHERE id = ?::uuid", laterLine));
        assertThat(lines(task)).hasSize(1);
        confirmAll(task);
        complete(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("STAGED"));

        // the top-up adds the line the order's hold lacks, and reopens the task
        call(post("/api/v1/fulfillment/tasks/" + task + "/top-up").header("Authorization", oc()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(pickStatus(task)).isEqualTo("PICKING");
        assertThat(lines(task)).hasSize(2);
        assertThat(lines(task).get(1).path("sequenceNo").asInt()).as("after the last stop").isEqualTo(2);
        assertThat(lines(task).get(1).path("lpn").asText()).isEqualTo(laterLpn);
        assertThat(lines(task).get(0).path("status").asText()).as("the staged line stays staged").isEqualTo("STAGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Integer.class,
                order.orderId())).as("the same task, no second one").isOne();

        // the new carton is staged too, the task is STAGED again
        confirmAll(task);
        assertThat(locationOf(laterLpn)).isEqualTo(STAGING);
        complete(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("STAGED"));
        call(post("/api/v1/fulfillment/tasks/" + task + "/top-up").header("Authorization", oc()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(pickStatus(task)).as("nothing new: it stays STAGED").isEqualTo("STAGED");

        // a picker cannot top a task up (it is the manager's or coordinator's decision)
        call(post("/api/v1/fulfillment/tasks/" + task + "/top-up").header("Authorization", picker()))
                .andExpect(status().isForbidden());

        dispatchTransfer(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        assertThat(cartonStatus(laterLpn)).isEqualTo("CONSUMED");
    }

    // ---- stock in a staging area is not moved by hand ------------------------------------------

    @Test
    @DisplayName("a person cannot move a carton into or out of a staging area: STAGING_MOVE_NOT_ALLOWED, nothing moves")
    void aHandMoveOutOfOrIntoStagingIsRefused() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID task = stagingTask(order);
        start(task);
        JsonNode line = lines(task).get(0);
        String staged = line.path("lpn").asText();
        confirm(task, line).andExpect(status().isOk());
        assertThat(locationOf(staged)).isEqualTo(STAGING);

        // a free carton into it
        String free = jdbc.queryForObject("""
                SELECT p.lpn FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE p.location_code = ? AND p.status = 'IN_STOCK' AND s.reserved = 0 AND s.on_hand > 0
                 ORDER BY p.lpn LIMIT 1""", String.class, SOFA_BIN);
        Throwable into = catchThrowable(() -> moveByHand(List.of(free), STAGING));
        assertThat(into).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.STAGING_MOVE_NOT_ALLOWED));
        assertThat(locationOf(free)).isEqualTo(SOFA_BIN);

        // the staged carton out of it: refused for the staging area, whatever else could be said of a held carton
        Throwable out = catchThrowable(() -> moveByHand(List.of(staged), SOFA_BIN));
        assertThat(out).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) out).errorCode()).isIn(ErrorCode.STAGING_MOVE_NOT_ALLOWED,
                ErrorCode.STOCK_PACKAGE_HELD);
        assertThat(locationOf(staged)).isEqualTo(STAGING);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement m JOIN inventory.stock_package p ON p.id = m.to_stock_package_id
                 WHERE p.lpn IN (?, ?) AND m.reference_type = 'MOVE_TASK'""", Integer.class, free, staged)).isZero();
    }

    private void moveByHand(List<String> lpns, String to) {
        tx.execute(s -> inventory.moveStockPackages(new MoveStockPackagesCommand(lpns, to, MoveReference.MOVE_TASK,
                Identifiers.newId(), managerHcm)));
    }

    // ---- destination and assignee only while PENDING -------------------------------------------

    @Test
    @DisplayName("the destination and the assignee change while the task is PENDING and are PICK_TASK_STATE_CONFLICT once "
            + "picking started")
    void destinationAndAssigneeOnlyWhilePending() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID task = createTask(order, "");
        call(get("/api/v1/fulfillment/tasks/" + task).header("Authorization", oc()))
                .andExpect(jsonPath("$.data.destination").value("DISPATCH"))
                .andExpect(jsonPath("$.data.stagingLocationCode").doesNotExist());

        call(put("/api/v1/fulfillment/tasks/" + task + "/destination").header("Authorization", manager())
                .contentType("application/json").content("{\"destination\":\"STAGING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.destination").value("STAGING"))
                .andExpect(jsonPath("$.data.stagingLocationCode").value(STAGING));
        call(put("/api/v1/fulfillment/tasks/" + task + "/assignment").header("Authorization", oc())
                .contentType("application/json").content("{\"assignedUserId\":\"" + otherPickerHcm + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignedUserId").value(otherPickerHcm.toString()));
        call(put("/api/v1/fulfillment/tasks/" + task + "/destination").header("Authorization", oc())
                .contentType("application/json").content("{\"destination\":\"DISPATCH\",\"stagingLocationCode\":\"" + STAGING + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.destination").value("DISPATCH"))
                .andExpect(jsonPath("$.data.stagingLocationCode").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT staging_location_code FROM fulfillment.pick WHERE id = ?", String.class,
                task)).isNull();

        // the new assignee starts it: from now on neither changes
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start")
                .header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk());
        call(put("/api/v1/fulfillment/tasks/" + task + "/destination").header("Authorization", oc())
                .contentType("application/json").content("{\"destination\":\"STAGING\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));
        call(put("/api/v1/fulfillment/tasks/" + task + "/assignment").header("Authorization", oc())
                .contentType("application/json").content("{\"assignedUserId\":\"" + pickerHcm + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT assigned_user_id FROM fulfillment.pick WHERE id = ?", UUID.class, task))
                .isEqualTo(otherPickerHcm);
        assertThat(jdbc.queryForObject("SELECT destination FROM fulfillment.pick WHERE id = ?", String.class, task))
                .isEqualTo("DISPATCH");

        // a picker cannot change either
        call(put("/api/v1/fulfillment/tasks/" + createTask(confirmedOrder(1), "") + "/destination")
                .header("Authorization", picker()).contentType("application/json").content("{\"destination\":\"STAGING\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("STAGING_AREA_INVALID (422) for a bin, an area that is not a staging area, a staging area of another warehouse "
            + "or none named while the warehouse has two; a named active one is taken")
    void theStagingAreaMustBeAnActiveOneOfTheWarehouse() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID task = createTask(order, "");
        String url = "/api/v1/fulfillment/tasks/" + task + "/destination";

        for (String code : List.of(SOFA_BIN, "HCM-PACK01", "HCM-NOWHERE")) {
            call(put(url).header("Authorization", manager()).contentType("application/json")
                    .content("{\"destination\":\"STAGING\",\"stagingLocationCode\":\"" + code + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("STAGING_AREA_INVALID"));
        }
        String otherWarehouseArea = stagingArea("QSB" + (int) (Math.random() * 900 + 100), true);
        call(put(url).header("Authorization", manager()).contentType("application/json")
                .content("{\"destination\":\"STAGING\",\"stagingLocationCode\":\"" + otherWarehouseArea + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("STAGING_AREA_INVALID"));
        assertThat(jdbc.queryForObject("SELECT destination FROM fulfillment.pick WHERE id = ?", String.class, task))
                .isEqualTo("DISPATCH");

        // a second staging area in HCM: none named is ambiguous, a named one is taken
        String second = stagingArea("QSH" + (int) (Math.random() * 900 + 100), false);
        try {
            call(put(url).header("Authorization", manager()).contentType("application/json")
                    .content("{\"destination\":\"STAGING\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("STAGING_AREA_INVALID"));
            call(put(url).header("Authorization", manager()).contentType("application/json")
                    .content("{\"destination\":\"STAGING\",\"stagingLocationCode\":\"" + second + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.stagingLocationCode").value(second));
            // and a task created for STAGING with two areas to choose from needs one named
            call(post("/api/v1/fulfillment/tasks").header("Authorization", oc()).contentType("application/json")
                    .content("{\"orderId\":\"" + confirmedOrder(1).orderId() + "\",\"assignedUserId\":\"" + pickerHcm
                            + "\",\"destination\":\"STAGING\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("STAGING_AREA_INVALID"));
        } finally {
            // the task no longer names the extra area, so that can be removed (a cancelled task could not be edited)
            tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.pick SET destination = 'DISPATCH', "
                    + "staging_location_code = NULL WHERE id = ? AND status = 'PENDING'", task));
        }
    }

    /** A STAGING area, in HCM or in a warehouse of its own; removed in {@link #cleanUp}. Returns its location code. */
    private String stagingArea(String name, boolean otherWarehouse) {
        UUID warehouse = otherWarehouse ? Identifiers.newId() : HCM;
        String prefix = otherWarehouse ? name.substring(0, 3) + "W" : "HCM";
        String code = prefix + "-" + name;
        tx.executeWithoutResult(s -> {
            if (otherWarehouse) {
                jdbc.update("""
                        INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                         version, created_at)
                        VALUES (?, 'Staging test warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                        warehouse, prefix);
            }
            UUID location = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            is_pickable, is_putaway_target, status)
                    VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')""", location, warehouse, code);
            jdbc.update("""
                    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,
                                                rotation, is_obstacle, status)
                    VALUES (?, ?, ?, ?, 'STAGING', 'Test staging', 1, 1, 2, 2, 0, FALSE, 'ACTIVE')""",
                    Identifiers.newId(), warehouse, location, name);
        });
        extraAreas.add(code);
        if (otherWarehouse) {
            extraWarehouses.add(warehouse);
        }
        return code;
    }
}
