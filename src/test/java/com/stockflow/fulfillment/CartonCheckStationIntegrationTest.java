package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The carton check and labelling station (team plan 4C-6; kltn-docs 08 §4-§5, BR-01..BR-11; 17 BR-12, BR-16) through the
 * real filter chain, permission aspect, warehouse scope and database: the queue, starting a check, scanning the LPN of
 * every carton the task picked (another order's carton refused, a repeat writing nothing), the label (only with a
 * measured carton spec, its version going up on a reprint, the PDF), the first label locking the order, a damaged
 * carton removed and put into quarantine, completing in the order of 08 §4.4 (one shipment per completion, a short
 * one only when the order allows it and with a note, an unpaid BEFORE_DELIVERY installment a warning and no block) and
 * a labelled carton loading and shipping through the load plan like a sealed parcel did.
 *
 * <p>Its own context, hence its own database: labelled orders cannot be cancelled, so their stock does not come back.
 * The demo carton units declare no size, so each test that labels sets it ({@link CartonStationSupport}).</p>
 */
@org.junit.jupiter.api.Disabled("Round 4 of the C3 tests, still disabled: its order()/pay() helpers are stubs (they used placeOrder, "
        + "payment terms and the money ledger, all gone) and its stock and carton-spec corrections are the demo"
        + " SOFA-3S-GREY in cartons of one, while a contract order is the customer's own SKU in cartons of two "
        + "or more (14 BR-04, BR-08): the orders must come from support/ConfirmedOrders and every count be re-"
        + "derived. The station itself is exercised end to end by FtlDispatch, ControlledUnload and LoadPlan "
        + "(CartonStationSupport). See reports/C3-tests.md, Round 4.")
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.fulfillment.load-planning.solver-wall-limit=PT2S",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.test.context=carton-check-station"})
class CartonCheckStationIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";
    private static final String QUARANTINE_AREA = "HCM-QC01";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired InventoryControlService itemControls;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID manager;
    private UUID picker;
    private UUID packer;
    private UUID otherPacker;
    private UUID packerElsewhere;
    private UUID accountant;
    private UUID customerUser;
    private UUID customer;
    private UUID carrier;
    private UUID truck5t;

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        packer = staff("WAREHOUSE_STAFF", HCM);
        otherPacker = staff("WAREHOUSE_STAFF", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        packerElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        accountant = staff("ACCOUNTANT");
        customerUser = staff("CUSTOMER");
        customer = customerOf(customerUser);
        carrier = tx.execute(s -> ReferenceRows.truckCarrier(entityManager, "Xe tải Kiểm Thùng"));
        truck5t = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-5T'", UUID.class);
        CartonStationSupport.giveCartonsASize(tx, jdbc);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "K" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
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

    private UUID customerOf(UUID user) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Carton check customer', ?, 'ACTIVE', 0, NOW())""", id, user,
                "carton-check-" + id + "@example.com"));
        return id;
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

    private ResultActions asPacker(MockHttpServletRequestBuilder request) throws Exception {
        return as(packer, "WAREHOUSE_STAFF", request);
    }

    private ResultActions asManager(MockHttpServletRequestBuilder request) throws Exception {
        return as(manager, "WAREHOUSE_MANAGER", request);
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "ORDER_COORDINATOR", request);
    }

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(packer, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private Long currentTermsVersion(UUID customerId) {
        var terms = customers.creditTerms(customerId);
        return terms.configured() ? terms.version() : null;
    }

    /** {@code sofas} more demo sofas: whole cartons of one, because this class shares one database. */
    private void stock(int sofas) {
        tx.executeWithoutResult(s -> CartonRows.stock(jdbc, SOFA.code(), "HCM-A02-1-A", sofas, 1, null,
                Instant.now().minus(400, ChronoUnit.DAYS)));
    }

    /** An order of the test customer, its money received as {@code term} wants, released at HCM, partial delivery allowed. */
    private OrderSummary order(Object term, Object policy, int sofas, long price) {
        throw new UnsupportedOperationException("to be rebuilt on support/ConfirmedOrders (round 3)");
    }

    private OrderSummary order(int sofas) {
        return order(null, null, sofas, 12_000_000);
    }

    private void pay(OrderSummary order, BigDecimal amount) {
        throw new UnsupportedOperationException("to be rebuilt on support/ConfirmedOrders (round 3)");
    }

    private UUID openTask(OrderSummary order, String destinationBody) throws Exception {
        return UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"" + destinationBody + "}"))
                .andExpect(status().isCreated())).path("taskId").asText());
    }

    private void pickEveryLine(UUID task) throws Exception {
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, lpn FROM fulfillment.pick_line WHERE pick_id = ? ORDER BY sequence_no", task)) {
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                    + "/confirmation"), "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\""
                    + line.get("lpn") + "\"}")).andExpect(status().isOk());
        }
    }

    /** OC opens the task, the picker picks every line by scan and completes picking. */
    private UUID pickedTask(OrderSummary order) throws Exception {
        UUID task = openTask(order, "");
        pickEveryLine(task);
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        return task;
    }

    private List<String> lpns(UUID task) {
        return CartonStationSupport.pickedLpns(jdbc, task);
    }

    private String url(UUID task, String path) {
        return BASE + "/tasks/" + task + path;
    }

    private ResultActions start(UUID user, String role, UUID task) throws Exception {
        return as(user, role, post(url(task, "/packing-start")));
    }

    private ResultActions scan(UUID user, String role, UUID task, String lpn) throws Exception {
        return as(user, role, body(post(url(task, "/carton-checks")), "{\"lpn\":\"" + lpn + "\"}"));
    }

    private ResultActions label(UUID task, String content) throws Exception {
        return content == null ? asPacker(post(url(task, "/carton-labels")))
                : asPacker(body(post(url(task, "/carton-labels")), content));
    }

    private ResultActions session(UUID task) throws Exception {
        return asPacker(get(url(task, "/packing-session")));
    }

    private Map<String, Object> carton(String lpn) {
        return jdbc.queryForMap("SELECT * FROM fulfillment.package WHERE lpn = ? ORDER BY created_at DESC LIMIT 1", lpn);
    }

    private String sessionStatus(UUID task) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ---- the station: scanning, labels, the order lock -------------------------------------------

    @Test
    @DisplayName("queue, start, scan: another order's carton is CARTON_NOT_IN_TASK, a second worker PACKING_TAKEN, a manager "
            + "steps in, a repeat scan writes nothing, lower case is accepted")
    void scanningAcceptsOnlyTheCartonsOfThisTask() throws Exception {
        OrderSummary order = order(3);
        UUID task = pickedTask(order);
        OrderSummary elsewhere = order(1);
        UUID otherTask = pickedTask(elsewhere);
        String foreign = lpns(otherTask).getFirst();
        List<String> mine = lpns(task);
        assertThat(mine).hasSize(3);

        // The picked order waits in the queue of its warehouse, not started.
        JsonNode queue = data(asPacker(get(BASE + "/packing-queue?warehouseId=" + HCM + "&size=200"))
                .andExpect(status().isOk()));
        JsonNode row = null;
        for (JsonNode item : queue.path("items")) {
            if (item.path("taskId").asText().equals(task.toString())) {
                row = item;
            }
        }
        assertThat(row).as("the picked task is in the queue").isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("PENDING");
        assertThat(row.path("orderNumber").asText()).isEqualTo(order.orderNumber());

        // Read before it starts: the picked cartons show TO_CHECK and are not written.
        session(task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.cartons.length()").value(3))
                .andExpect(jsonPath("$.data.cartons[0].status").value("TO_CHECK"))
                .andExpect(jsonPath("$.data.cartons[0].packageId").doesNotExist())
                .andExpect(jsonPath("$.data.skusWithoutCartonSpec.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE pack_id = (SELECT id FROM "
                + "fulfillment.pack WHERE pick_id = ?)", Long.class, task)).isZero();

        // Nothing is checked before the session starts.
        scan(packer, "WAREHOUSE_STAFF", task, mine.getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));

        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.startedBy").value(packer.toString()))
                .andExpect(jsonPath("$.data.cartons.length()").value(3))
                .andExpect(jsonPath("$.data.cartons[0].status").value("TO_CHECK"))
                .andExpect(jsonPath("$.data.cartons[0].packageId").isNotEmpty())
                .andExpect(jsonPath("$.data.cartons[0].labelCode").value(order.orderNumber() + "-C1"))
                .andExpect(jsonPath("$.data.orderLocked").value(false));
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE pack_id = (SELECT id FROM "
                + "fulfillment.pack WHERE pick_id = ?)", Long.class, task)).as("a repeat start adds no row").isEqualTo(3);

        // 08 BR-06: a second worker is turned away, a manager is not.
        start(otherPacker, "WAREHOUSE_STAFF", task).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        scan(otherPacker, "WAREHOUSE_STAFF", task, mine.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        scan(packerElsewhere, "WAREHOUSE_STAFF", task, mine.get(0)).andExpect(status().isForbidden());
        scan(manager, "WAREHOUSE_MANAGER", task, mine.get(0).toLowerCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(0) + "')].status")
                        .value(org.hamcrest.Matchers.contains("CHECKED")));
        assertThat(carton(mine.get(0)).get("checked_by")).isEqualTo(manager);

        // BR-05: only a carton picked for this task.
        scan(packer, "WAREHOUSE_STAFF", task, foreign).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTON_NOT_IN_TASK"));
        scan(packer, "WAREHOUSE_STAFF", task, "LPN-DOES-NOT-EXIST").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTON_NOT_IN_TASK"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE lpn = ?", Long.class, foreign))
                .as("the other order's carton has no row in this session").isZero();
        asPacker(body(post(url(task, "/carton-checks")), "{\"lpn\":\"\"}")).andExpect(status().isBadRequest());

        // A repeat of the same scan writes nothing.
        Object version = carton(mine.get(0)).get("version");
        Object checkedAt = carton(mine.get(0)).get("checked_at");
        scan(packer, "WAREHOUSE_STAFF", task, mine.get(0)).andExpect(status().isOk());
        assertThat(carton(mine.get(0))).containsEntry("version", version).containsEntry("checked_at", checkedAt);
    }

    @Test
    @DisplayName("no label without a measured carton spec (422 naming the SKU, nothing labelled, order not locked); with one "
            + "the carton takes its size, weight and volumetric weight and the order is locked: no cancellation, a pending "
            + "request expires; the PDF has a page per carton; a reprint raises the version")
    void labelsNeedACartonSpecAndLockTheOrder() throws Exception {
        CartonStationSupport.clearCartonSize(tx, jdbc, SOFA.code());
        OrderSummary order = order(2);
        String cancel = "/api/v1/orders/" + order.orderId() + "/cancellation";
        String request = data(as(customerUser, "CUSTOMER", body(post(cancel), "{\"reasonCode\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isAccepted())).path("requestId").asText();
        UUID task = pickedTask(order);
        List<String> mine = lpns(task);
        CartonStationSupport.startAndScanAll(asPackerWorker(), jdbc, task);
        session(task).andExpect(jsonPath("$.data.skusWithoutCartonSpec[0]").value(SOFA.code()));

        // 08 BR-07: no size, no label; the answer names the SKU and nothing is labelled, locked or half done.
        label(task, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARTON_SPEC_UNMEASURED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(SOFA.code()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE lpn IN (?, ?) AND status = 'LABELED'",
                Long.class, mine.get(0), mine.get(1))).isZero();
        assertThat(orderLockedAt(order)).isNull();
        shippingLabelsPdf(task, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTON_NOT_CHECKED"));

        // The manager completes the carton spec on the item; the same call now labels.
        CartonStationSupport.giveCartonsASize(tx, jdbc);
        label(task, "{\"lpns\":[\"" + mine.get(0).toLowerCase() + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderLocked").value(true))
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(0) + "')].status")
                        .value(org.hamcrest.Matchers.contains("LABELED")))
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(1) + "')].status")
                        .value(org.hamcrest.Matchers.contains("CHECKED")));
        Map<String, Object> labelled = carton(mine.get(0));
        assertThat(labelled).containsEntry("status", "LABELED").containsEntry("label_version", 1);
        assertThat((BigDecimal) labelled.get("length_cm")).isEqualByComparingTo("215");
        assertThat((BigDecimal) labelled.get("width_cm")).isEqualByComparingTo("95");
        assertThat((BigDecimal) labelled.get("height_cm")).isEqualByComparingTo("90");
        assertThat((BigDecimal) labelled.get("weight_kg")).isEqualByComparingTo("52.5");
        assertThat((BigDecimal) labelled.get("dim_weight_kg")).as("215 x 95 x 90 / 6000 (08 BR-09)")
                .isEqualByComparingTo("306.375");
        assertThat(labelled.get("labeled_by")).isEqualTo(packer);
        assertThat(orderLockedAt(order)).as("the first label locks the order").isNotNull();
        Object lockedAt = orderLockedAt(order);

        // 17 BR-12: a labelled order is not cancelled; the customer's pending request closes with a note.
        assertThat(code(catchThrowable(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.cancel(order.orderId(), new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                        "too late", null, null)))))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        Map<String, Object> closed = jdbc.queryForMap("SELECT status, decided_by, decision_note FROM "
                + "ordering.order_cancellation_request WHERE id = ?::uuid", request);
        assertThat(closed.get("status")).isEqualTo("EXPIRED");
        assertThat(closed.get("decided_by")).isNull();
        assertThat((String) closed.get("decision_note")).contains("labelled");
        oc(body(post("/api/v1/orders/" + order.orderId() + "/cancellation-requests/" + request + "/approval"), "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_CANCELLATION_REQUEST_NOT_PENDING"));
        // A second label time never replaces the first. Label every CHECKED one again (mine.get(1) is still CHECKED).
        label(task, null).andExpect(status().isOk());
        // Review L2: the same call repeated after it worked is 200 with nothing changed, not CARTON_NOT_CHECKED.
        Object labelledVersion = carton(mine.get(1)).get("version");
        label(task, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(1) + "')].status")
                        .value(org.hamcrest.Matchers.contains("LABELED")));
        assertThat(carton(mine.get(1))).containsEntry("version", labelledVersion).containsEntry("label_version", 1);
        assertThat(orderLockedAt(order)).isEqualTo(lockedAt);

        // The PDF: one 100 x 150 mm page per labelled carton, nothing changed by printing it.
        Object version = carton(mine.get(0)).get("version");
        byte[] pdf = shippingLabelsPdf(task, null).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(100 / 25.4f * 72, org.assertj.core.data.Offset.offset(0.5f));
        }
        try (PDDocument document = Loader.loadPDF(shippingLabelsPdf(task, "{\"lpns\":[\"" + mine.get(1) + "\"]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())) {
            assertThat(document.getNumberOfPages()).isOne();
        }
        assertThat(carton(mine.get(0))).containsEntry("version", version);
        shippingLabelsPdf(task, "{\"lpns\":[\"LPN-NOT-OURS\"]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTON_NOT_IN_TASK"));
        as(customerUser, "CUSTOMER", body(post(url(task, "/shipping-labels")), "{}")).andExpect(status().isForbidden());

        // 08 BR-08: a reprint raises the version; the old label is void.
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(0) + "')].labelVersion")
                        .value(org.hamcrest.Matchers.contains(2)));
        assertThat(carton(mine.get(0))).containsEntry("label_version", 2);
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint"))).andExpect(status().isOk());
        assertThat(carton(mine.get(0))).containsEntry("label_version", 3);
        as(otherPacker, "WAREHOUSE_STAFF", post(url(task, "/cartons/" + mine.get(0) + "/label-reprint")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        // Review L1: the tablet sends the version on the label it replaces; a retry after it worked changes nothing.
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint?fromVersion=3"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(0) + "')].labelVersion")
                        .value(org.hamcrest.Matchers.contains(4)));
        Object reprinted = carton(mine.get(0)).get("version");
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint?fromVersion=3"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + mine.get(0) + "')].labelVersion")
                        .value(org.hamcrest.Matchers.contains(4)));
        assertThat(carton(mine.get(0))).containsEntry("label_version", 4).containsEntry("version", reprinted);
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint?fromVersion=0")))
                .andExpect(status().isBadRequest());
    }

    private CartonStationSupport.Worker asPackerWorker() {
        return this::asPacker;
    }

    private Object orderLockedAt(OrderSummary order) {
        return jdbc.queryForObject("SELECT first_labeled_at FROM ordering.customer_order WHERE id = ?", Object.class,
                order.orderId());
    }

    private ResultActions shippingLabelsPdf(UUID task, String content) throws Exception {
        return asPacker(body(post(url(task, "/shipping-labels")), content == null ? "{}" : content)
                .accept("application/pdf", "application/json"));
    }

    // ---- completing --------------------------------------------------------------------------------

    @Test
    @DisplayName("completing follows the check order: an unscanned carton first, then the labels, then the shipment - one "
            + "READY_TO_DISPATCH per completion; order PACKED, session COMPLETED; the same requestId again answers it")
    void completingMakesOneShipment() throws Exception {
        OrderSummary order = order(2);
        UUID task = pickedTask(order);
        List<String> mine = lpns(task);
        String completion = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";

        asPacker(body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk());
        asPacker(body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_INCOMPLETE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(mine.get(0))));
        scan(packer, "WAREHOUSE_STAFF", task, mine.get(0)).andExpect(status().isOk());
        scan(packer, "WAREHOUSE_STAFF", task, mine.get(1)).andExpect(status().isOk());
        asPacker(body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_INCOMPLETE"));
        label(task, null).andExpect(status().isOk());

        String shipment = data(asPacker(body(post(url(task, "/packing-completion")), completion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.packStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.paymentDue").doesNotExist())).path("shipmentId").asText();
        assertThat(jdbc.queryForMap("SELECT status, package_count, partial, code FROM fulfillment.shipment WHERE id = ?::uuid",
                shipment)).containsEntry("status", "READY_TO_DISPATCH").containsEntry("package_count", 2)
                .containsEntry("partial", false).containsEntry("code", order.orderNumber() + "-S1");
        assertThat(jdbc.queryForList("SELECT lpn FROM fulfillment.package WHERE shipment_id = ?::uuid ORDER BY lpn",
                String.class, shipment)).containsExactlyInAnyOrderElementsOf(mine);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(sessionStatus(task)).isEqualTo("COMPLETED");

        // The same request again, and another worker asking: the same shipment, nothing new.
        asPacker(body(post(url(task, "/packing-completion")), completion)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipmentId").value(shipment));
        as(otherPacker, "WAREHOUSE_STAFF", body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipmentId").value(shipment));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment WHERE order_id = ?", Long.class,
                order.orderId())).isOne();

        // A label printed again after completion, until the order leaves: by the worker who started the check or a
        // manager only (08 BR-06, review L7).
        as(otherPacker, "WAREHOUSE_STAFF", post(url(task, "/cartons/" + mine.get(0) + "/label-reprint")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        assertThat(carton(mine.get(0))).containsEntry("label_version", 1);
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint"))).andExpect(status().isOk());
        asManager(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint?fromVersion=2"))).andExpect(status().isOk());
        assertThat(carton(mine.get(0))).containsEntry("label_version", 3);
        // A carton in a shipment is not removed.
        asPacker(body(post(url(task, "/cartons/" + mine.get(0) + "/removal")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));   // a completed session is not worked;
        // PACKAGE_SHIPPED is the domain's answer (CartonCheckTest) for a session still in progress
    }

    @Test
    @DisplayName("a damaged carton is removed: complete without 'partial' is PACKING_INCOMPLETE, partial where the order does not "
            + "allow it PACK_PARTIAL_NOT_ALLOWED, where it does but with no note VALIDATION_FAILED, then a partial shipment "
            + "and the session stays IN_PROGRESS; the removed carton waits in the put-backs and ends whole in QUARANTINE")
    void aDamagedCartonIsRemovedAndGoesToQuarantine() throws Exception {
        OrderSummary order = order(3);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = FALSE WHERE id = ?", order.orderId()));
        UUID task = pickedTask(order);
        List<String> mine = lpns(task);
        String damaged = mine.get(2);
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk());
        for (String lpn : mine) {
            scan(packer, "WAREHOUSE_STAFF", task, lpn).andExpect(status().isOk());
        }
        // Damage after the pick is no endpoint any more (5-6): a damaged carton is removed here, whole.
        String damagedLine = jdbc.queryForObject("SELECT id::text FROM fulfillment.pick_line WHERE lpn = ?", String.class,
                damaged);
        asPacker(post(BASE + "/tasks/" + task + "/pick-lines/" + damagedLine + "/damage-after-pick"))
                .andExpect(status().is4xxClientError());
        asPacker(body(post(url(task, "/cartons/" + damaged + "/removal")), "{\"note\":\"corner crushed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + damaged + "')].status")
                        .value(org.hamcrest.Matchers.contains("REMOVED")))
                .andExpect(jsonPath("$.data.cartons[?(@.lpn=='" + damaged + "')].removalReason")
                        .value(org.hamcrest.Matchers.contains("DAMAGED")));
        Map<String, Object> removed = carton(damaged);
        assertThat(removed).containsEntry("status", "REMOVED").containsEntry("removal_reason", "DAMAGED")
                .containsEntry("removal_note", "corner crushed").containsEntry("removed_by", packer);
        // Again: 200, nothing changes.
        Object version = removed.get("version");
        asPacker(body(post(url(task, "/cartons/" + damaged + "/removal")), "{}")).andExpect(status().isOk());
        assertThat(carton(damaged)).containsEntry("version", version);
        // A removed carton is neither scanned nor labelled.
        scan(packer, "WAREHOUSE_STAFF", task, damaged).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));
        label(task, "{\"lpns\":[\"" + damaged + "\"]}").andExpect(status().isConflict());
        label(task, null).andExpect(status().isOk());

        // 08 §4.4 step 8: short by a carton.
        asPacker(body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_INCOMPLETE"));
        asPacker(body(post(url(task, "/packing-completion")), "{\"partial\":true,\"note\":\"one carton damaged\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_PARTIAL_NOT_ALLOWED"));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        asPacker(body(post(url(task, "/packing-completion")), "{\"partial\":true}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        String shipment = data(asPacker(body(post(url(task, "/packing-completion")),
                "{\"partial\":true,\"note\":\"one carton damaged\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("IN_PROGRESS"))).path("shipmentId").asText();
        assertThat(jdbc.queryForMap("SELECT package_count, partial, partial_note FROM fulfillment.shipment WHERE id = ?::uuid",
                shipment)).containsEntry("package_count", 2).containsEntry("partial", true)
                .containsEntry("partial_note", "one carton damaged");
        assertThat(sessionStatus(task)).isEqualTo("IN_PROGRESS");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("still picking until it is handed over")
                .isNotEqualTo(OrderStatus.CANCELLED);

        // The removed carton waits in the put-back queue, goes into a quarantine area only, whole.
        String lineId = jdbc.queryForObject("SELECT id::text FROM fulfillment.pick_line WHERE lpn = ?", String.class, damaged);
        asPacker(get(BASE + "/put-backs?warehouseId=" + HCM + "&size=200")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].lpn")
                        .value(org.hamcrest.Matchers.contains(damaged)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].quarantine")
                        .value(org.hamcrest.Matchers.contains(true)));
        String putBack = url(task, "/pick-lines/" + lineId + "/put-back");
        String bin = jdbc.queryForObject("SELECT location_code FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                lineId);
        asPacker(body(post(putBack), "{\"locationCode\":\"" + bin + "\",\"lpn\":\"" + damaged + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PUT_BACK_LOCATION_INVALID"));
        asPacker(body(post(putBack), "{\"locationCode\":\"" + QUARANTINE_AREA + "\",\"lpn\":\"" + mine.get(0) + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        asPacker(body(post(putBack), "{\"locationCode\":\"" + QUARANTINE_AREA + "\",\"lpn\":\"" + damaged + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUT_BACK"))
                .andExpect(jsonPath("$.data.putBackLocationCode").value(QUARANTINE_AREA));
        assertThat(jdbc.queryForMap("SELECT location_code, status FROM inventory.stock_package WHERE lpn = ?", damaged))
                .containsEntry("location_code", QUARANTINE_AREA).containsEntry("status", "IN_STOCK");
        assertThat(jdbc.queryForMap("SELECT s.location_code, s.status, s.on_hand FROM inventory.stock_item s JOIN "
                + "inventory.stock_package p ON p.id = s.stock_package_id WHERE p.lpn = ?", damaged))
                .containsEntry("location_code", QUARANTINE_AREA).containsEntry("status", "QUARANTINE")
                .containsEntry("on_hand", 1);
        assertThat(jdbc.queryForList("SELECT to_status FROM inventory.stock_movement WHERE movement_type = 'PUT_BACK' "
                + "AND reference_id = ?::uuid", String.class, lineId)).containsExactly("QUARANTINE");
        // The same put-back again: 200, one ledger line.
        asPacker(body(post(putBack), "{\"locationCode\":\"" + QUARANTINE_AREA + "\",\"lpn\":\"" + damaged + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'PUT_BACK' "
                + "AND reference_id = ?::uuid", Long.class, lineId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_item s JOIN inventory.stock_package p ON "
                + "p.id = s.stock_package_id WHERE p.lpn = ? AND s.status = 'AVAILABLE'", Long.class, damaged))
                .as("never AVAILABLE, not even for an instant").isZero();
    }

    @Test
    @DisplayName("an unpaid BEFORE_DELIVERY installment is a warning at the session and at completion, never a block")
    void anUnpaidBeforeDeliveryInstallmentWarnsButDoesNotBlock() throws Exception {
        OrderSummary order = order(1);
        // A contract order as 4B-6 writes it: one BEFORE_DELIVERY installment still unpaid (rows written around the
        // contract tables, which this test is not about).
        tx.executeWithoutResult(t -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                statement.execute("""
                        INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id,
                            contract_number, order_number, seq, name, milestone, amount, paid_amount, currency, status,
                            created_at)
                        VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'HD-CK-1', '%s', 1,
                                'Before delivery', 'BEFORE_DELIVERY', 2000000, 0, 'VND', 'NOT_DUE', NOW())"""
                        .formatted(order.orderId(), customer, order.orderNumber()));
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        }));
        UUID task = pickedTask(order);
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentDue.amount").value(2000000))
                .andExpect(jsonPath("$.data.paymentDue.currency").value("VND"));
        JsonNode done = CartonStationSupport.complete(asPackerWorker(), jdbc, json, task, null);
        assertThat(done.path("shipmentId").asText()).isNotBlank();
        assertThat(done.path("paymentDue").path("amountDueBeforeHandover").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        // The completed session still shows it.
        session(task).andExpect(jsonPath("$.data.paymentDue.amount").value(2000000));
    }

    // ---- a cancelled order, a staged task ----------------------------------------------------------

    @Test
    @DisplayName("an order cancelled before any label: its cartons are REMOVED (ORDER_CANCELLED), the session CANCELLED, the "
            + "lines wait in the put-backs to go back onto a bin")
    void anOrderCancelledBeforeAnyLabel() throws Exception {
        OrderSummary order = order(2);
        UUID task = pickedTask(order);
        List<String> mine = lpns(task);
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk());
        scan(packer, "WAREHOUSE_STAFF", task, mine.get(0)).andExpect(status().isOk());

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "changed mind", null, null)));
        Await.until("the check cancelled", ASYNC, () -> "CANCELLED".equals(sessionStatus(task)));

        for (String lpn : mine) {
            assertThat(carton(lpn)).containsEntry("status", "REMOVED").containsEntry("removal_reason", "ORDER_CANCELLED")
                    .containsEntry("removed_by", null);
        }
        assertThat(orderLockedAt(order)).isNull();
        String lineId = jdbc.queryForObject("SELECT id::text FROM fulfillment.pick_line WHERE lpn = ?", String.class,
                mine.get(0));
        asPacker(get(BASE + "/put-backs?warehouseId=" + HCM + "&size=200")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].lpn")
                        .value(org.hamcrest.Matchers.contains(mine.get(0))))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].quarantine")
                        .value(org.hamcrest.Matchers.contains(false)));
        String bin = jdbc.queryForObject("SELECT location_code FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                lineId);
        // A cancelled order's carton goes back onto a bin, not into quarantine.
        asPacker(body(post(url(task, "/pick-lines/" + lineId + "/put-back")), "{\"locationCode\":\"" + QUARANTINE_AREA
                + "\",\"lpn\":\"" + mine.get(0) + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PUT_BACK_LOCATION_INVALID"));
        asPacker(body(post(url(task, "/pick-lines/" + lineId + "/put-back")), "{\"locationCode\":\"" + bin
                + "\",\"lpn\":\"" + mine.get(0) + "\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT s.status, s.location_code FROM inventory.stock_item s JOIN "
                + "inventory.stock_package p ON p.id = s.stock_package_id WHERE p.lpn = ?", mine.get(0)))
                .containsEntry("status", "AVAILABLE").containsEntry("location_code", bin);
        // The session of a cancelled order is not worked.
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a STAGED task is PACKING_NOT_READY at the station until the manager moves it to dispatch (07 BR-11, 08 BR-01)")
    void aStagedTaskWaitsForTheDispatchTransfer() throws Exception {
        OrderSummary order = order(1);
        UUID task = openTask(order, ",\"destination\":\"STAGING\"");
        pickEveryLine(task);
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("STAGED"));

        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_NOT_READY"));
        asPacker(body(post(url(task, "/packing-completion")), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_NOT_READY"));
        asManager(post(url(task, "/dispatch-transfer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"));

        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartons.length()").value(1));
    }

    // ---- loading and shipping ----------------------------------------------------------------------

    @Test
    @DisplayName("a LABELED carton loads and ships through the load plan: plan, book, load by scanning its LPN, hand over; "
            + "the order's lines come from the pick lines; the order is SHIPPED")
    void aLabeledCartonShipsThroughTheLoadPlan() throws Exception {
        OrderSummary order = order(2);
        UUID task = pickedTask(order);
        UUID shipment = CartonStationSupport.ship(asPackerWorker(), jdbc, json, task, null);
        List<String> mine = lpns(task);

        String plan = data(dispatch(body(post(BASE + "/load-plans"), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\""
                + truck5t + "\"],\"selectionMode\":\"SINGLE_TYPE\"}")).andExpect(status().isCreated()))
                .path("loadPlanId").asText();
        JsonNode planView = data(dispatch(get(BASE + "/load-plans/" + plan)).andExpect(status().isOk()));
        for (String step : List.of("/calculation", "/confirmation")) {
            planView = data(dispatch(body(post(BASE + "/load-plans/" + plan + step), "{\"expectedVersion\":"
                    + planView.path("version").asLong() + "}")).andExpect(status().isOk()));
        }
        assertThat(planView.path("status").asText()).isEqualTo("CONFIRMED");
        String load = planView.path("vehicles").get(0).path("load").path("loadId").asText();
        dispatch(body(post(BASE + "/loads/" + load + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-123.45\",\"waybillNo\":\"WB-" + load + "\"}")).andExpect(status().isOk());
        dispatch(post(BASE + "/loads/" + load + "/loading-start")).andExpect(status().isOk());
        JsonNode view = data(dispatch(get(BASE + "/loads/" + load)).andExpect(status().isOk()));
        assertThat(view.path("expectedPackageCount").asInt()).isEqualTo(2);

        // Each carton is scanned by the LPN on its label; the whole carton count must be on board.
        dispatch(body(post(BASE + "/loads/" + load + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                + "\",\"packageCode\":\"" + mine.get(0).toLowerCase() + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        dispatch(body(post(BASE + "/loads/" + load + "/handover"), "{\"version\":"
                + dispatchVersion(load) + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"signed\":true}"))
                .andExpect(status().isConflict());
        dispatch(body(post(BASE + "/loads/" + load + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                + "\",\"packageCode\":\"" + mine.get(1) + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadedPackageCount").value(2));
        dispatch(body(post(BASE + "/loads/" + load + "/handover"), "{\"version\":" + dispatchVersion(load)
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"signed\":true}")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, shipment))
                .isEqualTo("HANDED_OVER");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        // What the order was told it shipped is what the pick lines took (no copy on the carton).
        assertThat(orders.findById(order.orderId()).orElseThrow().lifecycle().lines())
                .extracting(OrderSummary.LineProgress::shippedQuantity).containsExactly(2);
    }

    @Test
    @DisplayName("a carton spec corrected after the check reaches the cartons when a label is printed again or the vehicle "
            + "is planned; once a plan holds them their measures stay (08 edge case, review M2)")
    void aCorrectedCartonSpecReachesTheCartonsBeforeTheVehicleIsPlanned() throws Exception {
        OrderSummary order = order(2);
        UUID task = pickedTask(order);
        UUID shipment = CartonStationSupport.ship(asPackerWorker(), jdbc, json, task, null);
        List<String> mine = lpns(task);
        assertThat((BigDecimal) carton(mine.get(0)).get("length_cm")).isEqualByComparingTo("215");

        // The manager finds the real carton is 230 cm long and 55 kg, and corrects the spec on the item.
        CartonStationSupport.correctCartonSize(tx, jdbc, SOFA.code(), "230", "95", "90", "55");
        // The first carton's label is printed again: it takes the corrected spec at once.
        asPacker(post(url(task, "/cartons/" + mine.get(0) + "/label-reprint"))).andExpect(status().isOk());
        assertThat((BigDecimal) carton(mine.get(0)).get("length_cm")).isEqualByComparingTo("230");
        assertThat((BigDecimal) carton(mine.get(0)).get("weight_kg")).isEqualByComparingTo("55");
        assertThat((BigDecimal) carton(mine.get(0)).get("dim_weight_kg")).as("230 x 95 x 90 / 6000")
                .isEqualByComparingTo("327.750");
        assertThat((BigDecimal) carton(mine.get(1)).get("length_cm")).as("no reprint yet").isEqualByComparingTo("215");

        // Its vehicle is planned: the other carton takes the corrected spec as the plan takes it.
        String plan = data(dispatch(body(post(BASE + "/load-plans"), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\""
                + truck5t + "\"],\"selectionMode\":\"SINGLE_TYPE\"}")).andExpect(status().isCreated()))
                .path("loadPlanId").asText();
        for (String lpn : mine) {
            Map<String, Object> row = carton(lpn);
            assertThat((BigDecimal) row.get("length_cm")).as(lpn).isEqualByComparingTo("230");
            assertThat((BigDecimal) row.get("weight_kg")).as(lpn).isEqualByComparingTo("55");
            assertThat((BigDecimal) row.get("dim_weight_kg")).as(lpn).isEqualByComparingTo("327.750");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_plan_package WHERE load_plan_id = ?::uuid "
                + "AND active", Long.class, plan)).isEqualTo(2);

        // Corrected again while the plan holds them: a reprint raises the version but keeps the planned measures.
        CartonStationSupport.correctCartonSize(tx, jdbc, SOFA.code(), "240", "95", "90", "60");
        asPacker(post(url(task, "/cartons/" + mine.get(1) + "/label-reprint"))).andExpect(status().isOk());
        assertThat(carton(mine.get(1))).containsEntry("label_version", 2);
        assertThat((BigDecimal) carton(mine.get(1)).get("length_cm")).as("planned: frozen").isEqualByComparingTo("230");
        JsonNode planView = data(dispatch(get(BASE + "/load-plans/" + plan)).andExpect(status().isOk()));
        data(dispatch(body(post(BASE + "/load-plans/" + plan + "/calculation"), "{\"expectedVersion\":"
                + planView.path("version").asLong() + "}")).andExpect(status().isOk()));
        assertThat((BigDecimal) carton(mine.get(0)).get("length_cm")).isEqualByComparingTo("230");
    }

    private long dispatchVersion(String load) throws Exception {
        return data(dispatch(get(BASE + "/loads/" + load)).andExpect(status().isOk())).path("version").asLong();
    }

    // ---- the carton spec ---------------------------------------------------------------------------

    @Test
    @DisplayName("the repack station's seven parcel writes are gone with 4C-7: no handler answers (404), nothing is written")
    void repackParcelWritesAreGone() throws Exception {
        OrderSummary order = order(1);
        UUID task = pickedTask(order);
        start(packer, "WAREHOUSE_STAFF", task).andExpect(status().isOk());
        long rows = jdbc.queryForObject("SELECT count(*) FROM fulfillment.package p JOIN fulfillment.pack k ON k.id = "
                + "p.pack_id WHERE k.pick_id = ?", Long.class, task);
        UUID parcel = jdbc.queryForObject("SELECT p.id FROM fulfillment.package p JOIN fulfillment.pack k ON k.id = "
                + "p.pack_id WHERE k.pick_id = ?", UUID.class, task);
        String scan = "{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA.code() + "\",\"quantity\":1}";
        String seal = "{\"weightKg\":5,\"lengthCm\":40,\"widthCm\":30,\"heightCm\":20}";
        String line = order.lines().getFirst().lineId().toString();
        List<MockHttpServletRequestBuilder> writes = List.of(
                body(post(url(task, "/packages")), "{}"),
                body(post(url(task, "/package-kits")), "{\"orderLineId\":\"" + line + "\"}"),
                body(post(url(task, "/packages/" + parcel + "/scans")), scan),
                delete(url(task, "/packages/" + parcel + "/lines/" + line + "?quantity=1")),
                body(post(url(task, "/packages/" + parcel + "/seal")), seal),
                post(url(task, "/packages/" + parcel + "/reopening")),
                delete(url(task, "/packages/" + parcel)));
        for (MockHttpServletRequestBuilder write : writes) {
            asPacker(write).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package p JOIN fulfillment.pack k ON k.id = "
                + "p.pack_id WHERE k.pick_id = ?", Long.class, task)).as("no parcel row, no renumbering").isEqualTo(rows);
        // the station itself is unaffected
        scan(packer, "WAREHOUSE_STAFF", task, lpns(task).getFirst()).andExpect(status().isOk());
    }

    private void write(String sql, Object... args) {
        tx.executeWithoutResult(s -> jdbc.update(sql, args));
    }

    @Test
    @DisplayName("the carton spec of a SKU: its one carton unit; the default shipping one among several; none when several have "
            + "no default or there is no carton unit; measured only with a size and a weight")
    void theCartonSpecOfASku() {
        // The table's carton unit, put through every shape and back (a SKU needs a catalogue variant to exist at all).
        String sku = "TABLE-OAK-160";
        UUID first = jdbc.queryForObject("SELECT u.id FROM inventory.uom_conversions u JOIN inventory.inventory_items i "
                + "ON i.id = u.inventory_item_id WHERE i.sku = ? AND NOT u.is_base AND u.active", UUID.class, sku);
        UUID second = Identifiers.newId();
        try {
            assertThat(itemControls.cartonSpec(new Sku("CK-NO-SUCH-SKU"))).as("unknown SKU").isEmpty();
            write("UPDATE inventory.uom_conversions SET active = FALSE WHERE id = ?", first);
            assertThat(itemControls.cartonSpec(new Sku(sku))).as("no carton unit").isEmpty();

            write("UPDATE inventory.uom_conversions SET active = TRUE, length_cm = NULL, width_cm = NULL, height_cm = NULL, "
                    + "weight_kg = NULL WHERE id = ?", first);
            var spec = itemControls.cartonSpec(new Sku(sku)).orElseThrow();
            assertThat(spec.unitOfMeasure()).isEqualTo("CARTON");
            assertThat(spec.unitsPerCarton()).isEqualTo(1);
            assertThat(spec.measured()).as("no size, no weight declared").isFalse();

            write("UPDATE inventory.uom_conversions SET length_cm = 40, width_cm = 30, height_cm = 25, weight_kg = 6.5 "
                    + "WHERE id = ?", first);
            spec = itemControls.cartonSpec(new Sku(sku)).orElseThrow();
            assertThat(spec.measured()).isTrue();
            assertThat(spec.lengthCm()).isEqualByComparingTo("40");
            assertThat(spec.weightKg()).isEqualByComparingTo("6.5");

            write("UPDATE inventory.uom_conversions SET weight_kg = NULL WHERE id = ?", first);
            assertThat(itemControls.cartonSpec(new Sku(sku)).orElseThrow().measured()).as("a size without a weight")
                    .isFalse();

            write("INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active) "
                    + "SELECT ?, id, 'CARTON24', 24, TRUE FROM inventory.inventory_items WHERE sku = ?", second, sku);
            assertThat(itemControls.cartonSpec(new Sku(sku))).as("two units, none the default for shipping").isEmpty();
            write("UPDATE inventory.uom_conversions SET default_shipping = TRUE WHERE id = ?", second);
            assertThat(itemControls.cartonSpec(new Sku(sku)).orElseThrow().unitOfMeasure()).isEqualTo("CARTON24");
            write("UPDATE inventory.uom_conversions SET active = FALSE, default_shipping = FALSE WHERE id = ?", second);
            assertThat(itemControls.cartonSpec(new Sku(sku)).orElseThrow().unitOfMeasure())
                    .as("an inactive unit is not a candidate").isEqualTo("CARTON");
        } finally {
            write("DELETE FROM inventory.uom_conversions WHERE id = ?", second);
            write("UPDATE inventory.uom_conversions SET active = TRUE WHERE id = ?", first);
            CartonStationSupport.giveCartonsASize(tx, jdbc);
        }
    }
}
