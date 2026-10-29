package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.payment.internal.service.ReceivableService;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * G4-2a (SCRUM-288/292/293) through the real filter chain, permission aspect, warehouse scope and database: a parcel
 * load opened for one carrier, parcels scanned against its manifest (every wrong scan refused with its own code, a
 * retried scan harmless), the signature that hands the shipments over and moves the order in the same transaction with
 * the COD fixed per shipment (09 BR-03/04), the outcomes the coordinator records and what they bill, and the
 * customer's view of their shipments (BR-05).
 *
 * <p>Its own context, hence its own database: shipped orders keep their stock.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.test.context=dispatch-handover"})
class DispatchHandoverIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";
    /** KIT-FORCE: the sofa ships as 2 boxes; packed in an ordinary package, it needs a reason. */
    private static final String ONE_CARTON = "{\"kitOverrideReason\":\"test: packed as one carton\"}";
    private static final String MEASURES = "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired RecordedEvents.Recorder recorded;

    private UUID coordinator;
    private UUID manager;
    private UUID picker;
    private UUID dispatcher;
    private UUID secondDispatcher;
    private UUID dispatcherElsewhere;
    private UUID accountant;
    private UUID customerUser;
    private UUID otherCustomerUser;
    private UUID customer;
    private UUID ghn;
    private UUID ghtk;

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        secondDispatcher = staff("WAREHOUSE_STAFF", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        dispatcherElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        accountant = staff("ACCOUNTANT");
        customerUser = staff("CUSTOMER");
        otherCustomerUser = staff("CUSTOMER");
        customer = customerOf(customerUser);
        customerOf(otherCustomerUser);
        ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        ghtk = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHTK'", UUID.class);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "D" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
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
                VALUES (?, ?, 'Dispatch customer', ?, 'ACTIVE', 0, NOW())""", id, user, "dispatch-" + id + "@example.com"));
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

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(dispatcher, "WAREHOUSE_STAFF", request);
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "ORDER_COORDINATOR", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    /** SCRUM-484: terms that exist are changed against the version read; the first set carries none. */
    private Long currentTermsVersion(UUID customerId) {
        var terms = customers.creditTerms(customerId);
        return terms.configured() ? terms.version() : null;
    }

    /** An order of the test customer on {@code term}, its money received as that term wants, released at HCM. */
    private OrderSummary order(PaymentTerm term, DepositBalancePolicy policy, int sofas, long price) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true,
                CommercialTerm.valueOf(term.name()), new BigDecimal("30"), new BigDecimal("10000000000"), 30,
                "test terms", accountant, currentTermsVersion(customer), policy));
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, sofas, Money.vnd(price), null)), term, null, null));
        // Split shipments throughout: Sales allowed them on the order (PACK-PARTIAL, lead's decision 2026-10-21).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        BigDecimal total = order.total().amount();
        if (term == PaymentTerm.PREPAID) {
            pay(order, total);
        } else if (term == PaymentTerm.DEPOSIT) {
            pay(order, total.multiply(new BigDecimal("0.3")));
        }
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        return releases.release(order.orderId(), HCM, coordinator);
    }

    private void pay(OrderSummary order, BigDecimal amount) {
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), amount, "VND",
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), null, "payment", accountant,
                List.of(new ReceivableService.OrderPayment(order.orderId(), amount))));
    }

    private OrderStatus statusOf(OrderSummary order) {
        return orders.findById(order.orderId()).orElseThrow().status();
    }

    /** OC opens the task, the picker picks every line by scan and completes picking. */
    private UUID pickedTask(OrderSummary order) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                    + "/confirmation"), "{\"locationCode\":\"" + line.get("location_code") + "\",\"scannedCode\":\""
                    + line.get("sku") + "\",\"quantity\":" + line.get("allocated_qty") + "}")).andExpect(status().isOk());
        }
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        return task;
    }

    /** One shipment of {@code perPackage} sofas in each new package, completed (partial when asked). */
    private UUID ship(UUID task, boolean partial, int... perPackage) throws Exception {
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        for (int units : perPackage) {
            UUID parcel = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                    + "/packages"), ONE_CARTON)).andExpect(status().isCreated())).path("packageId").asText());
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans"),
                    "{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA.code() + "\",\"quantity\":"
                            + units + "}")).andExpect(status().isOk());
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal"),
                    MEASURES)).andExpect(status().isOk());
        }
        String completion = partial ? "{\"partial\":true,\"note\":\"ready goods first\"}" : "{}";
        return UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packing-completion"), completion)).andExpect(status().isOk())).path("shipmentId").asText());
    }

    private List<String> labels(UUID shipment) {
        return jdbc.queryForList("SELECT label_code FROM fulfillment.package WHERE shipment_id = ? ORDER BY package_no",
                String.class, shipment);
    }

    private JsonNode openLoad(UUID carrier, UUID... shipments) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (UUID s : shipments) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(s).append('"');
        }
        return data(dispatch(body(post(BASE + "/loads"), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + carrier + "\",\"shipmentIds\":[" + ids
                + "],\"vehiclePlate\":\"51C-123.45\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("LOADING")));
    }

    private ResultActions scan(UUID user, String load, UUID scanId, String code) throws Exception {
        return as(user, "WAREHOUSE_STAFF", body(post(BASE + "/loads/" + load + "/scans"), "{\"scanId\":\"" + scanId
                + "\",\"packageCode\":\"" + code + "\"}"));
    }

    private ResultActions scan(String load, String code) throws Exception {
        return scan(dispatcher, load, UUID.randomUUID(), code);
    }

    private long version(String load) throws Exception {
        return data(dispatch(get(BASE + "/loads/" + load)).andExpect(status().isOk())).path("version").asLong();
    }

    private ResultActions handOver(UUID user, String load, long version, boolean signed) throws Exception {
        return as(user, "WAREHOUSE_STAFF", body(post(BASE + "/loads/" + load + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"vehiclePlate\":\"51C-999.99\","
                + "\"signatureRef\":\"MF-SIGNED-1\",\"signed\":" + signed
                + "}"));
    }

    private ResultActions handOver(String load) throws Exception {
        return handOver(dispatcher, load, version(load), true);
    }

    private ResultActions outcome(UUID shipment, String body) throws Exception {
        return oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), body));
    }

    private Map<String, Object> shipmentRow(UUID shipment) {
        return jdbc.queryForMap("SELECT status, carrier_id, load_id, cod_amount::bigint AS cod, handed_over_by "
                + "FROM fulfillment.shipment WHERE id = ?", shipment);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ---- the whole flow ------------------------------------------------------------------------

    @Test
    @DisplayName("prepaid stock order: load for its carrier, every scan rule, signature hands it over (order SHIPPED), "
            + "repeat harmless, manifest, customer view, delivered (order DELIVERED), who may do what")
    void aStockOrderLeavesOnASignedManifest() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 2, 12_000_000);
        UUID shipment = ship(pickedTask(order), false, 1, 1);
        List<String> parcels = labels(shipment);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PACKED);

        // 09 §4.1 step 2: the dispatcher chooses its carrier; a load opened for it takes it by default.
        dispatch(get(BASE + "/shipments?status=READY_TO_DISPATCH&warehouseId=" + HCM + "&size=200"))
                .andExpect(status().isOk());
        dispatch(body(put(BASE + "/shipments/" + shipment + "/carrier"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shipment.carrierName").value("Giao Hàng Nhanh"));
        String requestId = UUID.randomUUID().toString();
        String open = "{\"requestId\":\"" + requestId + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + ghn
                + "\"}";
        JsonNode load = data(dispatch(body(post(BASE + "/loads"), open)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.shipmentCount").value(1))
                .andExpect(jsonPath("$.data.expectedPackageCount").value(2))
                .andExpect(jsonPath("$.data.carrierCode").value("GHN")));
        String loadId = load.path("loadId").asText();
        assertThat(load.path("code").asText()).matches("LD-\\d{8}-\\d{4}");
        dispatch(body(post(BASE + "/loads"), open)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loadId").value(loadId));

        // Who may load: not the coordinator (reads only), not a customer, not another warehouse.
        oc(get(BASE + "/loads/" + loadId)).andExpect(status().isOk());
        as(coordinator, "ORDER_COORDINATOR", body(post(BASE + "/loads/" + loadId + "/scans"), "{\"scanId\":\""
                + UUID.randomUUID() + "\",\"packageCode\":\"" + parcels.get(0) + "\"}")).andExpect(status().isForbidden());
        as(customerUser, "CUSTOMER", get(BASE + "/loads")).andExpect(status().isForbidden());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(BASE + "/loads/" + loadId)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        dispatch(get(BASE + "/loads/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_FOUND"));

        // SCRUM-292: scans against the manifest.
        scan(loadId, "PKG-NOPE-1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_FOUND"));
        UUID scanId = UUID.randomUUID();
        scan(dispatcher, loadId, scanId, parcels.get(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        scan(dispatcher, loadId, scanId, parcels.get(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        scan(loadId, parcels.get(0).toLowerCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        scan(dispatcher, loadId, scanId, parcels.get(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_SCAN_ID_CONFLICT"));

        // SCRUM-293: not everything on board, unsigned, a stale version - each refused, nothing changes.
        handOver(dispatcher, loadId, version(loadId), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_FULLY_LOADED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(parcels.get(1))));
        scan(loadId, parcels.get(1)).andExpect(status().isOk());
        dispatch(delete(BASE + "/loads/" + loadId + "/scans/" + jdbc.queryForObject(
                "SELECT id FROM fulfillment.package WHERE label_code = ?", UUID.class, parcels.get(1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        long before = version(loadId);
        scan(loadId, parcels.get(1)).andExpect(status().isOk());
        handOver(dispatcher, loadId, version(loadId), false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("HANDOVER_NOT_SIGNED"));
        handOver(dispatcher, loadId, before, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        assertThat(shipmentRow(shipment)).containsEntry("status", "READY_TO_DISPATCH");
        recorded.clear();

        JsonNode dispatched = data(handOver(loadId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.carrierRepresentative").value("Nguyen Van Tai"))
                .andExpect(jsonPath("$.data.shipments[0].status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.data.shipments[0].codAmount").value(0)));
        assertThat(shipmentRow(shipment)).containsEntry("status", "HANDED_OVER").containsEntry("carrier_id", ghn)
                .containsEntry("load_id", UUID.fromString(loadId)).containsEntry("handed_over_by", dispatcher);
        OrderSummary shipped = orders.findById(order.orderId()).orElseThrow();
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().shipments()).singleElement().satisfies(s -> {
            assertThat(s.shipmentId()).isEqualTo(shipment);
            assertThat(s.carriage().carrierName()).isEqualTo("Giao Hàng Nhanh");
            assertThat(s.carriage().packageCount()).isEqualTo(2);
        });
        assertThat(recorded.ofType(ShipmentStatusChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("HANDED_OVER");
            assertThat(e.previousStatus()).isEqualTo("READY_TO_DISPATCH");
            assertThat(e.loadCode()).isEqualTo(dispatched.path("code").asText());
            assertThat(e.recordedBy()).isEqualTo(dispatcher);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'HANDED_OVER' AND recorded_by = ?", Long.class, shipment, dispatcher)).isEqualTo(1L);

        // Signed again (a retry, a second tap): 200, the same load, nothing twice.
        handOver(dispatcher, loadId, 0, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE order_id = ?", Long.class,
                order.orderId())).isEqualTo(1L);
        scan(loadId, parcels.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));

        // The printable manifest.
        dispatch(get(BASE + "/loads/" + loadId + "/manifest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.carrierName").value("Giao Hàng Nhanh"))
                .andExpect(jsonPath("$.data.vehiclePlate").value("51C-999.99"));
        JsonNode manifest = data(dispatch(get(BASE + "/loads/" + loadId + "/manifest")));
        assertThat(manifest.path("shipmentCount").asInt()).isEqualTo(1);
        assertThat(manifest.path("packageCount").asInt()).isEqualTo(2);
        assertThat(manifest.path("shipments").get(0).path("orderNumber").asText()).isEqualTo(order.orderNumber());
        assertThat(manifest.path("shipments").get(0).path("packages")).hasSize(2);
        assertThat(manifest.path("totalWeightKg").decimalValue()).isEqualByComparingTo("105");
        assertThat(manifest.path("signatureRef").asText()).isEqualTo("MF-SIGNED-1");

        // 09 BR-05: the customer reads their shipment, nothing internal; another customer does not.
        String mine = as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryState").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.shipments[0].status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.data.shipments[0].packageCount").value(2))
                .andExpect(jsonPath("$.data.shipments[0].carrierName").value("Giao Hàng Nhanh"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).doesNotContain(dispatcher.toString(), "handedOverBy", "recordedBy", "MF-SIGNED-1",
                "Nguyen Van Tai");
        as(otherCustomerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId()))
                .andExpect(status().isNotFound());

        // 09 BR-02: the coordinator records the outcome; warehouse staff and the manager may not (only RETURNED).
        as(dispatcher, "WAREHOUSE_STAFF", body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"),
                "{\"outcome\":\"DELIVERED\"}")).andExpect(status().isForbidden());
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"),
                "{\"outcome\":\"DELIVERED\"}")).andExpect(status().isForbidden());
        oc(body(post("/api/v1/orders/" + order.orderId() + "/delivery"), "{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_DELIVERY_VIA_SHIPMENT"));
        outcome(shipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.timeline[2].recordedBy").value(coordinator.toString()));
        outcome(shipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERED);
        as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryState").value("DELIVERED"))
                .andExpect(jsonPath("$.data.shipments[0].status").value("DELIVERED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = 'dispatch-load' "
                + "AND outcome = 'SUCCESS' AND resource_id = ?", Long.class, loadId)).isPositive();
    }

    @Test
    @DisplayName("deposit ON_DELIVERY in two shipments on two loads, the later one first: each handed over with the "
            + "COD its label printed (09 BR-04); delivered, each opens its carrier-collected receivable")
    void codStaysWhatTheLabelsSaid() throws Exception {
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.ON_DELIVERY, 2, 5_000_000); // 10M, 3M paid
        UUID task = pickedTask(order);
        UUID s1 = ship(task, true, 1);
        UUID s2 = ship(task, false, 1);
        long label1 = ((Number) shipmentRow(s1).get("cod")).longValue();
        long label2 = ((Number) shipmentRow(s2).get("cod")).longValue();
        assertThat(label1).isEqualTo(2_000_000L);
        assertThat(label2).isEqualTo(5_000_000L);

        // The second shipment goes first, with another carrier.
        String loadB = openLoad(ghtk, s2).path("loadId").asText();
        scan(loadB, labels(s2).getFirst()).andExpect(status().isOk());
        handOver(loadB).andExpect(status().isOk()).andExpect(jsonPath("$.data.codTotal").value(5_000_000));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        String loadA = openLoad(ghn, s1).path("loadId").asText();
        scan(loadA, labels(s1).getFirst()).andExpect(status().isOk());
        handOver(loadA).andExpect(status().isOk()).andExpect(jsonPath("$.data.codTotal").value(2_000_000));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(((Number) shipmentRow(s1).get("cod")).longValue()).isEqualTo(label1);
        assertThat(((Number) shipmentRow(s2).get("cod")).longValue()).isEqualTo(label2);
        assertThat(jdbc.queryForList("SELECT cod_amount::bigint FROM ordering.order_shipment WHERE order_id = ? "
                + "ORDER BY handed_over_at", Long.class, order.orderId())).containsExactly(5_000_000L, 2_000_000L);
        JsonNode manifest = data(dispatch(get(BASE + "/loads/" + loadA + "/manifest")));
        assertThat(manifest.path("codTotal").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(manifest.path("shipments").get(0).path("codChangedSinceLabel").asBoolean()).isFalse();

        outcome(s1, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        assertThat(statusOf(order)).as("one of two delivered").isEqualTo(OrderStatus.SHIPPED);
        outcome(s2, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERED);
        Await.until("carrier-collected receivables", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 2);
        assertThat(jdbc.queryForList("SELECT shipment_id::text || ':' || amount::bigint FROM payment.receivable "
                + "WHERE order_id = ?", String.class, order.orderId()))
                .containsExactlyInAnyOrder(s1 + ":2000000", s2 + ":5000000");
    }

    @Test
    @DisplayName("G3-Q4: a deposit order whose balance is due before delivery is refused at the signature (naming the "
            + "shipment) and nothing moves; paid, it goes. An order on hold is refused too; resolved, it goes")
    void unpaidOrHeldOrdersDoNotLeave() throws Exception {
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.BEFORE_DELIVERY, 1, 4_000_000);
        UUID shipment = ship(pickedTask(order), false, 1);
        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_BALANCE_NOT_PAID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                                shipment))));
        dispatch(get(BASE + "/loads/" + load)).andExpect(jsonPath("$.data.status").value("LOADING"));
        assertThat(shipmentRow(shipment)).containsEntry("status", "READY_TO_DISPATCH");
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PACKED);
        pay(order, new BigDecimal("2800000"));
        handOver(load).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);

        OrderSummary held = order(PaymentTerm.PREPAID, null, 1, 3_000_000);
        UUID heldShipment = ship(pickedTask(held), false, 1);
        String heldLoad = openLoad(ghn, heldShipment).path("loadId").asText();
        scan(heldLoad, labels(heldShipment).getFirst()).andExpect(status().isOk());
        oc(body(post("/api/v1/orders/" + held.orderId() + "/hold"), "{\"reasonCode\":\"ADDRESS\","
                + "\"note\":\"ward missing\"}")).andExpect(status().isOk());
        handOver(heldLoad).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_INVALID_TRANSITION"));
        oc(body(post("/api/v1/orders/" + held.orderId() + "/hold/resolution"), "{\"note\":\"ward fixed\"}"))
                .andExpect(status().isOk());
        handOver(heldLoad).andExpect(status().isOk());
        assertThat(statusOf(held)).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("the wrong parcel: of another carrier, open (not sealed), on another load, of a shipment not on the "
            + "manifest - each refused with its own code; a shipment of another carrier is refused on the manifest")
    void wrongParcelsAreRefused() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 3, 1_000_000);
        UUID task = pickedTask(order);
        UUID a = ship(task, true, 1);
        UUID b = ship(task, true, 1);
        dispatch(body(put(BASE + "/shipments/" + b + "/carrier"), "{\"carrierId\":\"" + ghtk + "\"}"))
                .andExpect(status().isOk());
        String load = openLoad(ghn, a).path("loadId").asText();
        scan(load, labels(b).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_OTHER_CARRIER"));
        dispatch(body(post(BASE + "/loads/" + load + "/shipments"), "{\"shipmentIds\":[\"" + b + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHIPMENT_OTHER_CARRIER"));
        String other = openLoad(ghtk, b).path("loadId").asText();
        scan(other, labels(b).getFirst()).andExpect(status().isOk());
        // b's parcel is on the GHTK load; its carrier changes only after it is taken off.
        dispatch(body(put(BASE + "/shipments/" + b + "/carrier"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHIPMENT_ON_ANOTHER_LOAD"));
        dispatch(body(post(BASE + "/loads/" + load + "/shipments"), "{\"shipmentIds\":[\"" + b + "\"]}"))
                .andExpect(status().isConflict());

        // An open parcel: its label exists but it is not sealed.
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID open = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packages"), ONE_CARTON)).andExpect(status().isCreated())).path("packageId").asText());
        String openLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE id = ?", String.class,
                open);
        scan(load, openLabel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_READY"));

        // A parcel of a shipment waiting for dispatch but not on this manifest.
        as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + open + "/scans"),
                "{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA.code() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + open + "/seal"), MEASURES))
                .andExpect(status().isOk());
        UUID c = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packing-completion"), "{}")).andExpect(status().isOk())).path("shipmentId").asText());
        scan(load, labels(c).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));
        // b's parcel is on the other load.
        dispatch(delete(BASE + "/loads/" + other + "/shipments/" + b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipmentCount").value(0));
        scan(other, labels(b).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));
        dispatch(body(post(BASE + "/loads/" + other + "/shipments"), "{\"shipmentIds\":[\"" + b + "\"]}"))
                .andExpect(status().isOk());
        scan(other, labels(b).getFirst()).andExpect(status().isOk());
        dispatch(body(post(BASE + "/loads/" + load + "/shipments"), "{\"shipmentIds\":[\"" + c + "\"]}"))
                .andExpect(status().isOk());
        String third = openLoad(ghn).path("loadId").asText();
        scan(third, labels(a).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));
        scan(load, labels(a).getFirst()).andExpect(status().isOk());
        scan(third, labels(a).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_ALREADY_LOADED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        jdbc.queryForObject("SELECT code FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                                String.class, load))));
        // The empty third load: nothing to sign; the manager calls it off.
        handOver(third).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LOAD_EMPTY"));
        dispatch(body(post(BASE + "/loads/" + third + "/cancellation"), "{\"reason\":\"opened twice\"}"))
                .andExpect(status().isForbidden());
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/loads/" + third + "/cancellation"),
                "{\"reason\":\"opened twice\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // a and c leave with GHN; the order has one unit left on b (GHTK): partly fulfilled, then shipped.
        scan(load, labels(c).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        handOver(other).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("09 §4.3: failed (customer absent) then delivered on a retry; another failed then returned; OTHER "
            + "needs a note; an outcome before the handover is refused; a credit order's delivery opens its receivable")
    void outcomesPerShipment() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 2, 1_000_000);
        UUID task = pickedTask(order);
        UUID first = ship(task, true, 1);
        UUID second = ship(task, false, 1);
        outcome(first, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_STATE_CONFLICT"));
        String load = openLoad(ghn, first, second).path("loadId").asText();
        scan(load, labels(first).getFirst()).andExpect(status().isOk());
        scan(load, labels(second).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk());

        outcome(first, "{\"outcome\":\"DELIVERY_FAILED\"}").andExpect(status().isBadRequest());
        outcome(first, "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"OTHER\"}")
                .andExpect(status().isBadRequest());
        outcome(first, "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"CUSTOMER_ABSENT\",\"note\":"
                + "\"gate locked, private note\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.failureReason").value("CUSTOMER_ABSENT"));
        assertThat(statusOf(order)).as("one failed, one still out").isEqualTo(OrderStatus.SHIPPED);
        outcome(second, "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"REFUSED\"}").andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERY_FAILED);
        String view = as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryState").value("DELIVERY_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(view).contains("CUSTOMER_ABSENT").doesNotContain("private note");

        outcome(first, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/shipments/" + second + "/delivery-outcome"),
                "{\"outcome\":\"RETURNED\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.status").value("RETURNED"));
        outcome(second, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isConflict());
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        assertThat(after.lifecycle().shipments()).extracting(s -> s.status().name())
                .containsExactlyInAnyOrder("DELIVERED", "RETURNED");
        assertThat(after.status()).as("one delivered, one back: what is left may ship again")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        OrderSummary credit = order(PaymentTerm.CREDIT, null, 1, 2_000_000);
        UUID creditShipment = ship(pickedTask(credit), false, 1);
        String creditLoad = openLoad(ghn, creditShipment).path("loadId").asText();
        scan(creditLoad, labels(creditShipment).getFirst()).andExpect(status().isOk());
        handOver(creditLoad).andExpect(status().isOk());
        outcome(creditShipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        Await.until("credit receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ? AND shipment_id = ?", Long.class,
                credit.orderId(), creditShipment) == 1);
        as(accountant, "ACCOUNTANT", get("/api/v1/receivables?customerId=" + customer)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("two dispatchers: scanning the same load at once both land; signing at once hands over once; two "
            + "loads claiming one shipment - one wins, the other 409")
    void twoDispatchersOnOneLoad() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 2, 1_000_000);
        UUID shipment = ship(pickedTask(order), false, 1, 1);
        List<String> parcels = labels(shipment);
        String load = openLoad(ghn, shipment).path("loadId").asText();

        // Tokens are minted first: the mocked decoder is stubbed per token and Mockito stubbing is not thread-safe.
        String first = token(dispatcher, "WAREHOUSE_STAFF");
        String second = token(secondDispatcher, "WAREHOUSE_STAFF");
        List<Integer> scans = race(() -> withToken(first, body(post(BASE + "/loads/" + load + "/scans"), "{\"scanId\":\""
                + UUID.randomUUID() + "\",\"packageCode\":\"" + parcels.get(0) + "\"}")), () -> withToken(second,
                body(post(BASE + "/loads/" + load + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                        + "\",\"packageCode\":\"" + parcels.get(1) + "\"}")));
        assertThat(scans).containsOnly(200);
        long version = version(load);
        assertThat(version(load)).isEqualTo(version);
        dispatch(get(BASE + "/loads/" + load)).andExpect(jsonPath("$.data.loadedPackageCount").value(2));
        String sign = "{\"version\":" + version + ",\"carrierRepresentative\":\"Tai\",\"vehiclePlate\":\"51C\","
                + "\"signed\":true}";
        String third = token(dispatcher, "WAREHOUSE_STAFF");
        String fourth = token(secondDispatcher, "WAREHOUSE_STAFF");
        List<Integer> signatures = race(() -> withToken(third, body(post(BASE + "/loads/" + load + "/handover"), sign)),
                () -> withToken(fourth, body(post(BASE + "/loads/" + load + "/handover"), sign)));
        assertThat(signatures).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'HANDED_OVER'", Long.class, shipment)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE id = ?", Long.class,
                shipment)).isEqualTo(1L);

        OrderSummary contested = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID one = ship(pickedTask(contested), false, 1);
        String[] bodies = new String[2];
        for (int i = 0; i < 2; i++) {
            bodies[i] = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\""
                    + ghn + "\",\"shipmentIds\":[\"" + one + "\"]}";
        }
        String fifth = token(dispatcher, "WAREHOUSE_STAFF");
        String sixth = token(secondDispatcher, "WAREHOUSE_STAFF");
        List<Integer> opened = race(() -> withToken(fifth, body(post(BASE + "/loads"), bodies[0])),
                () -> withToken(sixth, body(post(BASE + "/loads"), bodies[1])));
        assertThat(opened).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE shipment_id = ? "
                + "AND active", Long.class, one)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an order cancelled between packing and the handover: refused by the order (packed, decision "
            + "2026-10-17); were it cancelled, its shipment leaves the load and the signature goes without it")
    void cancelledBetweenPackingAndHandover() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID shipment = ship(pickedTask(order), false, 1);
        OrderSummary other = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID stays = ship(pickedTask(other), false, 1);
        String load = openLoad(ghn, shipment, stays).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        scan(load, labels(stays).getFirst()).andExpect(status().isOk());

        assertThat(code(catchThrowable(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.cancel(order.orderId(), new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                        "too late", null, null)))))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        // The fulfillment side of a cancellation (data written before the rule, or a future path): the shipment
        // waiting for dispatch is cancelled and leaves the manifest with its parcel.
        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(order.orderId(), order.orderNumber(),
                customer, "PACKED", "OTHER", "test", BigDecimal.ZERO, BigDecimal.ZERO, "VND", coordinator,
                Instant.now(), BigDecimal.ZERO)));
        Await.until("the shipment cancelled", ASYNC, () -> "CANCELLED".equals(shipmentRow(shipment).get("status")));
        dispatch(get(BASE + "/loads/" + load)).andExpect(jsonPath("$.data.shipmentCount").value(1))
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        scan(load, labels(shipment).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_READY"));
        handOver(load).andExpect(status().isOk()).andExpect(jsonPath("$.data.shipmentCount").value(1));
        assertThat(shipmentRow(stays)).containsEntry("status", "HANDED_OVER");
        assertThat(shipmentRow(shipment)).containsEntry("status", "CANCELLED");
    }

    // ---- review of G4-2a -----------------------------------------------------------------------------

    private UUID taskOf(UUID shipment) {
        return jdbc.queryForObject("SELECT k.pick_id FROM fulfillment.pack k JOIN fulfillment.shipment s "
                + "ON s.pack_id = k.id WHERE s.id = ?", UUID.class, shipment);
    }

    private JsonNode printLabel(UUID shipment) throws Exception {
        UUID parcel = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE shipment_id = ? ORDER BY package_no "
                + "LIMIT 1", UUID.class, shipment);
        return data(as(picker, "WAREHOUSE_STAFF", get(BASE + "/tasks/" + taskOf(shipment) + "/packages/" + parcel
                + "/label")).andExpect(status().isOk()));
    }

    private long cod(UUID shipment) {
        return ((Number) shipmentRow(shipment).get("cod")).longValue();
    }

    @Test
    @DisplayName("review #1: parcels packed before they recorded contents (V20261021000300 shapes) leave as the whole "
            + "order - marked so, COD the order's figure - and their outcomes take the whole-order path; the customer "
            + "sees one shipment")
    void parcelsWithoutContentsLeaveAsTheWholeOrder() throws Exception {
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.ON_DELIVERY, 1, 2_000_000); // COD 1.4M
        UUID shipment = ship(pickedTask(order), false, 1);
        // What V20261021000300 made of a pack completed by the old flow: a sealed parcel with no recorded contents.
        tx.executeWithoutResult(t -> jdbc.update("DELETE FROM fulfillment.package_line WHERE package_id IN "
                + "(SELECT id FROM fulfillment.package WHERE shipment_id = ?)", shipment));
        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk()).andExpect(jsonPath("$.data.codTotal").value(1_400_000));
        assertThat(jdbc.queryForObject("SELECT whole_order_handover FROM fulfillment.shipment WHERE id = ?",
                Boolean.class, shipment)).isTrue();
        assertThat(cod(shipment)).isEqualTo(1_400_000L);
        OrderSummary shipped = orders.findById(order.orderId()).orElseThrow();
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().shipments()).isEmpty();
        assertThat(shipped.lifecycle().wholeOrderCodAmount()).isEqualByComparingTo("1400000");
        as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments.length()").value(1))
                .andExpect(jsonPath("$.data.shipments[0].status").value("HANDED_OVER"));

        outcome(shipment, "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"CUSTOMER_ABSENT\"}")
                .andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERY_FAILED);
        as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].status").value("DELIVERY_FAILED"))
                .andExpect(jsonPath("$.data.shipments[0].failureReason").value("CUSTOMER_ABSENT"));
        outcome(shipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERED);
        Await.until("the carrier-collected receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 1);
        assertThat(jdbc.queryForObject("SELECT amount::bigint FROM payment.receivable WHERE order_id = ?", Long.class,
                order.orderId())).isEqualTo(1_400_000L);
        as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].status").value("DELIVERED"));
    }

    @Test
    @DisplayName("review #2: S1 on load B and S2 on load A of one deposit order signed at once - each takes its "
            + "label's COD, the advance counted once (no over-collection)")
    void concurrentSignaturesCountTheAdvanceOnce() throws Exception {
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.ON_DELIVERY, 5, 1_000_000); // 1.5M ahead
        UUID task = pickedTask(order);
        UUID s1 = ship(task, true, 1);      // 1M of goods, all of it paid ahead: COD 0
        UUID s2 = ship(task, false, 4);     // 4M, 0.5M of the advance left: COD 3.5M
        assertThat(cod(s1)).isZero();
        assertThat(cod(s2)).isEqualTo(3_500_000L);
        String loadA = openLoad(ghn, s2).path("loadId").asText();
        String loadB = openLoad(ghtk, s1).path("loadId").asText();
        scan(loadA, labels(s2).getFirst()).andExpect(status().isOk());
        scan(loadB, labels(s1).getFirst()).andExpect(status().isOk());
        String sign = "{\"version\":%d,\"carrierRepresentative\":\"Tai\",\"vehiclePlate\":\"51C\",\"signed\":true}";
        String a = token(dispatcher, "WAREHOUSE_STAFF");
        String b = token(secondDispatcher, "WAREHOUSE_STAFF");
        String bodyA = sign.formatted(version(loadA));
        String bodyB = sign.formatted(version(loadB));
        List<Integer> signed = race(() -> withToken(a, body(post(BASE + "/loads/" + loadA + "/handover"), bodyA)),
                () -> withToken(b, body(post(BASE + "/loads/" + loadB + "/handover"), bodyB)));
        assertThat(signed).containsOnly(200);
        assertThat(cod(s1)).isZero();
        assertThat(cod(s2)).isEqualTo(3_500_000L);
        assertThat(jdbc.queryForObject("SELECT SUM(cod_amount)::bigint FROM ordering.order_shipment WHERE order_id = ?",
                Long.class, order.orderId())).as("the balance, collected once").isEqualTo(3_500_000L);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("review #3: L1 {O1, O2} and L2 {O2, O1} signed at once - no deadlock, both orders shipped")
    void crossedLoadsDoNotDeadlock() throws Exception {
        OrderSummary o1 = order(PaymentTerm.PREPAID, null, 2, 1_000_000);
        OrderSummary o2 = order(PaymentTerm.PREPAID, null, 2, 1_000_000);
        UUID t1 = pickedTask(o1);
        UUID t2 = pickedTask(o2);
        UUID o1a = ship(t1, true, 1);
        UUID o2a = ship(t2, true, 1);
        UUID o1b = ship(t1, false, 1);
        UUID o2b = ship(t2, false, 1);
        String l1 = openLoad(ghn, o1a, o2b).path("loadId").asText();
        String l2 = openLoad(ghtk, o2a, o1b).path("loadId").asText();
        for (UUID s : List.of(o1a, o2b)) {
            scan(l1, labels(s).getFirst()).andExpect(status().isOk());
        }
        for (UUID s : List.of(o2a, o1b)) {
            scan(l2, labels(s).getFirst()).andExpect(status().isOk());
        }
        String sign = "{\"version\":%d,\"carrierRepresentative\":\"Tai\",\"vehiclePlate\":\"51C\",\"signed\":true}";
        String a = token(dispatcher, "WAREHOUSE_STAFF");
        String b = token(secondDispatcher, "WAREHOUSE_STAFF");
        String body1 = sign.formatted(version(l1));
        String body2 = sign.formatted(version(l2));
        List<Integer> signed = race(() -> withToken(a, body(post(BASE + "/loads/" + l1 + "/handover"), body1)),
                () -> withToken(b, body(post(BASE + "/loads/" + l2 + "/handover"), body2)));
        assertThat(signed).containsOnly(200);
        assertThat(statusOf(o1)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(statusOf(o2)).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("review #4 (09 BR-04): money arrived after the label was printed - the signature is refused "
            + "LABEL_OUTDATED naming the shipment until the label is reprinted with the order's figure")
    void anOutdatedLabelStopsTheHandover() throws Exception {
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.ON_DELIVERY, 1, 2_000_000); // 0.6M paid
        UUID shipment = ship(pickedTask(order), false, 1);
        assertThat(printLabel(shipment).path("codAmount").decimalValue()).isEqualByComparingTo("1400000");
        pay(order, new BigDecimal("400000"));
        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        String code = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class, shipment);
        handOver(load).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LABEL_OUTDATED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(code)));
        assertThat(statusOf(order)).as("nothing moved").isEqualTo(OrderStatus.PACKED);
        assertThat(printLabel(shipment).path("codAmount").decimalValue()).isEqualByComparingTo("1000000");
        handOver(load).andExpect(status().isOk()).andExpect(jsonPath("$.data.codTotal").value(1_000_000));
        assertThat(cod(shipment)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("review: a scan without scanId is accepted (one is made); two opens with one requestId at once both "
            + "answer the same load")
    void scanIdOptionalAndConcurrentOpenReplays() throws Exception {
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID shipment = ship(pickedTask(order), false, 1);
        String open = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\""
                + ghn + "\",\"shipmentIds\":[\"" + shipment + "\"]}";
        String a = token(dispatcher, "WAREHOUSE_STAFF");
        String b = token(secondDispatcher, "WAREHOUSE_STAFF");
        List<String> loads = race(() -> opened(a, open), () -> opened(b, open));
        assertThat(loads).allSatisfy(r -> assertThat(r).startsWith("201:"));
        assertThat(loads.get(0)).isEqualTo(loads.get(1));
        String load = loads.getFirst().substring(4);
        dispatch(body(post(BASE + "/loads/" + load + "/scans"), "{\"packageCode\":\"" + labels(shipment).getFirst()
                + "\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.loadedPackageCount").value(1));
        dispatch(body(post(BASE + "/loads/" + load + "/scans"), "{\"packageCode\":\"" + labels(shipment).getFirst()
                + "\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.loadedPackageCount").value(1));
    }

    @Test
    @DisplayName("FE #61: SYSTEM_ADMIN passes every role gate (carrier edit, load, scan, sign, cancel, outcome); "
            + "shipments carry their task (label reprint) and the currency of their COD")
    void systemAdminAndShipmentContext() throws Exception {
        UUID admin = staff("SYSTEM_ADMIN");
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID task = pickedTask(order);
        UUID shipment = ship(task, false, 1);
        JsonNode carrier = data(as(admin, "SYSTEM_ADMIN", body(post(BASE + "/carriers"),
                "{\"code\":\"SYSADM\",\"name\":\"Admin carrier\"}")).andExpect(status().isCreated()));
        as(admin, "SYSTEM_ADMIN", body(put(BASE + "/carriers/" + carrier.path("carrierId").asText()),
                "{\"code\":\"SYSADM\",\"name\":\"Admin carrier 2\",\"version\":" + carrier.path("version").asLong()
                        + "}")).andExpect(status().isOk());
        as(admin, "SYSTEM_ADMIN", body(put(BASE + "/shipments/" + shipment + "/carrier"), "{\"carrierId\":\"" + ghn
                + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.taskId").value(task.toString()))
                .andExpect(jsonPath("$.data.shipment.currency").value("VND"));
        JsonNode load = data(as(admin, "SYSTEM_ADMIN", body(post(BASE + "/loads"), "{\"requestId\":\""
                + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.shipments[0].taskId").value(task.toString()))
                .andExpect(jsonPath("$.data.shipments[0].currency").value("VND")));
        String loadId = load.path("loadId").asText();
        as(admin, "SYSTEM_ADMIN", body(post(BASE + "/loads/" + loadId + "/scans"), "{\"packageCode\":\""
                + labels(shipment).getFirst() + "\"}")).andExpect(status().isOk());
        as(admin, "SYSTEM_ADMIN", body(post(BASE + "/loads/" + loadId + "/handover"), "{\"version\":"
                + version(loadId) + ",\"carrierRepresentative\":\"Tai\",\"vehiclePlate\":\"51C\",\"signed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        as(admin, "SYSTEM_ADMIN", body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"),
                "{\"outcome\":\"DELIVERED\"}")).andExpect(status().isOk());
        dispatch(get(BASE + "/shipments?orderId=" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value(task.toString()))
                .andExpect(jsonPath("$.data.items[0].currency").value("VND"));
        String empty = openLoad(ghn).path("loadId").asText();
        as(admin, "SYSTEM_ADMIN", body(post(BASE + "/loads/" + empty + "/cancellation"), "{\"reason\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("carriers: the manager declares and edits them (409 on a taken code, stale version); staff read only; "
            + "an inactive carrier takes no new load")
    void theCarrierCatalogue() throws Exception {
        dispatch(get(BASE + "/carriers?size=50")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
        dispatch(body(post(BASE + "/carriers"), "{\"code\":\"AHA\",\"name\":\"Ahamove\"}"))
                .andExpect(status().isForbidden());
        JsonNode aha = data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"aha\",\"name\":\"Ahamove\",\"contactPhone\":\"0900000000\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.code").value("AHA")));
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"), "{\"code\":\"AHA\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_CODE_EXISTS"));
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + aha.path("carrierId").asText()),
                "{\"code\":\"AHA\",\"name\":\"Ahamove\",\"status\":\"INACTIVE\",\"version\":99}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + aha.path("carrierId").asText()),
                "{\"code\":\"AHA\",\"name\":\"Ahamove\",\"status\":\"INACTIVE\",\"version\":"
                        + aha.path("version").asLong() + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        dispatch(body(post(BASE + "/loads"), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"carrierId\":\"" + aha.path("carrierId").asText() + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_INACTIVE"));
        dispatch(body(post(BASE + "/loads"), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"carrierId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_NOT_FOUND"));
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", body(post(BASE + "/loads"), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- E2E QA 2026-10-21 ---------------------------------------------------------------------------

    /**
     * Brings its own sofas: this class shares one database and the demo stock is counted by the tests above, so each
     * of these adds what it is about to use instead of eating theirs.
     */
    private void stock(int sofas) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                    created_at, received_at)
                VALUES (?, ?, 'HCM-A02-1-A', ?, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '400 days')""",
                Identifiers.newId(), SOFA.code(), sofas));
    }

    /** Opens, labels, loads and delivers one credit order: its delivery opens one receivable. */
    private OrderSummary deliveredOnCredit(long price) throws Exception {
        OrderSummary order = order(PaymentTerm.CREDIT, null, 1, price);
        UUID shipment = ship(pickedTask(order), false, 1);
        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk());
        outcome(shipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        Await.until("credit receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 1);
        return order;
    }

    @Test
    @DisplayName("E2E F1: a pick without its pack row (seeded data) no longer 404s the task list - it reads as packing "
            + "not started, in the list, the PICKING filter and the task itself")
    void aTaskWithoutItsPackRowDoesNotBreakTheList() throws Exception {
        stock(1);
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        Map<String, Object> pack = jdbc.queryForMap("SELECT id, created_at FROM fulfillment.pack WHERE pick_id = ?",
                task);
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM fulfillment.pack WHERE pick_id = ?", task));
        try {
            for (String query : List.of("", "?status=PICKING", "?size=100")) {
                JsonNode page = data(oc(get(BASE + "/tasks" + query)).andExpect(status().isOk()));
                List<String> packStatuses = new ArrayList<>();
                for (JsonNode item : page.path("items")) {
                    if (item.path("taskId").asText().equals(task.toString())) {
                        packStatuses.add(item.path("packStatus").asText());
                    }
                }
                assertThat(packStatuses).as("the task is listed (%s)", query).containsExactly("PENDING");
            }
            as(picker, "WAREHOUSE_STAFF", get(BASE + "/tasks")).andExpect(status().isOk());
            oc(get(BASE + "/tasks/" + task)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pickStatus").value("PICKING"))
                    .andExpect(jsonPath("$.data.packStatus").value("PENDING"));
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("INSERT INTO fulfillment.pack (id, pick_id, status, created_at) "
                    + "VALUES (?, ?, 'PENDING', ?)", pack.get("id"), task, pack.get("created_at")));
        }
    }

    @Test
    @DisplayName("E2E F2: the label reprinted after LABEL_OUTDATED (630 000 -> 430 000) is what the load, the handover "
            + "answer and the signed manifest show - no 'COD changed since label'; a load holding an older copy is "
            + "corrected at the signature")
    void aReprintedLabelIsWhatTheManifestShows() throws Exception {
        stock(1);
        OrderSummary order = order(PaymentTerm.DEPOSIT, DepositBalancePolicy.ON_DELIVERY, 1, 900_000); // 270 000 paid
        UUID shipment = ship(pickedTask(order), false, 1);
        assertThat(printLabel(shipment).path("codAmount").decimalValue()).isEqualByComparingTo("630000");
        String load = openLoad(ghn, shipment).path("loadId").asText();
        dispatch(get(BASE + "/loads/" + load)).andExpect(jsonPath("$.data.shipments[0].labelCodAmount").value(630_000));
        pay(order, new BigDecimal("200000"));
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LABEL_OUTDATED"));

        assertThat(printLabel(shipment).path("codAmount").decimalValue()).isEqualByComparingTo("430000");
        dispatch(get(BASE + "/loads/" + load)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].labelCodAmount").value(430_000))
                .andExpect(jsonPath("$.data.codTotal").value(430_000));
        assertThat(jdbc.queryForObject("SELECT label_cod_amount::bigint FROM fulfillment.dispatch_load_shipment "
                + "WHERE shipment_id = ? AND active", Long.class, shipment)).isEqualTo(430_000L);
        JsonNode before = data(dispatch(get(BASE + "/loads/" + load + "/manifest")));
        assertThat(before.path("shipments").get(0).path("codAmount").decimalValue()).isEqualByComparingTo("430000");

        // A load recorded before reprints refreshed it still holds the old figure: the signature corrects it.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE fulfillment.dispatch_load_shipment SET label_cod_amount = 630000 WHERE shipment_id = ?",
                shipment));
        handOver(load).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].labelCodAmount").value(430_000))
                .andExpect(jsonPath("$.data.shipments[0].codAmount").value(430_000))
                .andExpect(jsonPath("$.data.codTotal").value(430_000));
        JsonNode manifest = data(dispatch(get(BASE + "/loads/" + load + "/manifest")));
        assertThat(manifest.path("shipments").get(0).path("codAmount").decimalValue()).isEqualByComparingTo("430000");
        assertThat(manifest.path("shipments").get(0).path("codChangedSinceLabel").asBoolean()).isFalse();
        assertThat(cod(shipment)).isEqualTo(430_000L);
    }

    @Test
    @DisplayName("E2E: a second cancellation request (the first rejected) closes as EXPIRED, with a note the customer "
            + "reads, when the order is packed - it can no longer be approved; handover and delivery leave it so")
    void aPendingCancellationRequestClosesWhenTheOrderIsPacked() throws Exception {
        stock(1);
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        String cancel = "/api/v1/orders/" + order.orderId() + "/cancellation";
        String first = data(as(customerUser, "CUSTOMER", body(post(cancel), "{\"reasonCode\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isAccepted())).path("requestId").asText();
        oc(body(post("/api/v1/orders/" + order.orderId() + "/cancellation-requests/" + first + "/rejection"),
                "{\"note\":\"already printed for you\"}")).andExpect(status().isOk());
        String second = data(as(customerUser, "CUSTOMER", body(post(cancel), "{\"reasonCode\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isAccepted())).path("requestId").asText();

        UUID task = pickedTask(order);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.order_cancellation_request WHERE id = ?::uuid",
                String.class, second)).as("still deciding while picked").isEqualTo("PENDING");
        UUID shipment = ship(task, false, 1);
        Map<String, Object> closed = jdbc.queryForMap("SELECT status, decided_by, decided_at, decision_note "
                + "FROM ordering.order_cancellation_request WHERE id = ?::uuid", second);
        assertThat(closed.get("status")).isEqualTo("EXPIRED");
        assertThat(closed.get("decided_by")).isNull();
        assertThat(closed.get("decided_at")).isNotNull();
        assertThat((String) closed.get("decision_note")).contains("PACKED").contains("can no longer be cancelled");
        oc(body(post("/api/v1/orders/" + order.orderId() + "/cancellation-requests/" + second + "/approval"), "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_CANCELLATION_REQUEST_NOT_PENDING"));

        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels(shipment).getFirst()).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk());
        outcome(shipment, "{\"outcome\":\"DELIVERED\"}").andExpect(status().isOk());
        as(customerUser, "CUSTOMER", get("/api/v1/orders/me/" + order.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancellationRequest.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.cancellationRequest.decisionNote").value(
                        org.hamcrest.Matchers.containsString("can no longer be cancelled")));
        assertThat(jdbc.queryForList("SELECT status FROM ordering.order_cancellation_request WHERE order_id = ? "
                + "ORDER BY requested_at", String.class, order.orderId())).containsExactly("REJECTED", "EXPIRED");
    }

    @Test
    @DisplayName("E2E: a request still pending when staff cancel the order closes as EXPIRED; an approved one stays "
            + "APPROVED (the approval is saved before the cancellation that closes pending ones)")
    void cancellingTheOrderClosesAPendingRequestButNotTheApprovedOne() throws Exception {
        stock(2);
        OrderSummary approved = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        String request = data(as(customerUser, "CUSTOMER", body(post("/api/v1/orders/" + approved.orderId()
                + "/cancellation"), "{\"reasonCode\":\"CUSTOMER_REQUEST\"}")).andExpect(status().isAccepted()))
                .path("requestId").asText();
        oc(body(post("/api/v1/orders/" + approved.orderId() + "/cancellation-requests/" + request + "/approval"), "{}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.order_cancellation_request WHERE id = ?::uuid",
                String.class, request)).isEqualTo("APPROVED");

        OrderSummary cancelled = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        String pending = data(as(customerUser, "CUSTOMER", body(post("/api/v1/orders/" + cancelled.orderId()
                + "/cancellation"), "{\"reasonCode\":\"CUSTOMER_REQUEST\"}")).andExpect(status().isAccepted()))
                .path("requestId").asText();
        oc(body(post("/api/v1/orders/" + cancelled.orderId() + "/admin-cancellation"),
                "{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT status, decision_note FROM ordering.order_cancellation_request "
                + "WHERE id = ?::uuid", pending)).containsEntry("status", "EXPIRED")
                .containsEntry("decision_note", "Closed: the order was cancelled.");
    }

    @Test
    @DisplayName("E2E: GET /receivables?orderId= returns that order's receivables only (it used to ignore the filter)")
    void receivablesFilterByOrder() throws Exception {
        stock(2);
        OrderSummary one = deliveredOnCredit(1_000_000);
        OrderSummary two = deliveredOnCredit(2_000_000);
        JsonNode page = data(as(accountant, "ACCOUNTANT", get("/api/v1/receivables?orderId=" + one.orderId()))
                .andExpect(status().isOk()));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("orderId").asText()).isEqualTo(one.orderId().toString());
        as(accountant, "ACCOUNTANT", get("/api/v1/receivables?orderId=" + two.orderId() + "&customerId=" + customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(two.orderId().toString()));
        as(accountant, "ACCOUNTANT", get("/api/v1/receivables?orderId=" + UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        as(accountant, "ACCOUNTANT", get("/api/v1/receivables?orderId=not-a-uuid")).andExpect(status().isBadRequest());
        as(customerUser, "CUSTOMER", get("/api/v1/receivables?orderId=" + one.orderId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E2E: packing-queue rows carry their status (the value the status filter matches): PENDING until a "
            + "packer takes the order, PACKING after")
    void packingQueueRowsCarryTheirStatus() throws Exception {
        stock(1);
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID task = pickedTask(order);
        java.util.function.Function<String, JsonNode> row = query -> {
            try {
                for (JsonNode item : data(as(picker, "WAREHOUSE_STAFF", get(BASE + "/packing-queue?size=100" + query))
                        .andExpect(status().isOk())).path("items")) {
                    if (item.path("taskId").asText().equals(task.toString())) {
                        return item;
                    }
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(row.apply("").path("status").asText()).isEqualTo("PENDING");
        assertThat(row.apply("").path("packStatus").asText()).isEqualTo("PENDING");
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        assertThat(row.apply("&status=PACKING").path("status").asText()).isEqualTo("PACKING");
        assertThat(row.apply("&status=PENDING")).isNull();
    }

    private String opened(String token, String open) throws Exception {
        var response = mvc.perform(body(post(BASE + "/loads"), open).header("Authorization", token)).andReturn()
                .getResponse();
        return response.getStatus() + ":" + json.readTree(response.getContentAsString()).path("data").path("loadId")
                .asText();
    }

    private int withToken(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token)).andReturn().getResponse().getStatus();
    }

    @SafeVarargs
    private static <T> List<T> race(Callable<T>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- kits ("bộ", SCRUM-287 PR K, LP2-6) -------------------------------------------------------------------

    @Test
    @DisplayName("LP2-6: a kit of 2 leaves only with both parts scanned - a part left behind holds back its shipment, "
            + "named with its kit; the load and the manifest show each part's kit")
    void everyPartOfAKitIsScanned() throws Exception {
        stock(1);
        OrderSummary order = order(PaymentTerm.PREPAID, null, 1, 1_000_000);
        UUID task = pickedTask(order);
        UUID line = order.lines().getFirst().lineId();
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        JsonNode kit = data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/package-kits"),
                "{\"orderLineId\":\"" + line + "\"}")).andExpect(status().isCreated()));
        List<String> parts = new ArrayList<>();
        kit.path("packages").forEach(p -> parts.add(p.path("packageId").asText()));
        assertThat(parts).hasSize(2);
        as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + parts.get(0) + "/scans"),
                "{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA.code() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        for (String part : parts) {
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + part + "/seal"),
                    MEASURES)).andExpect(status().isOk());
        }
        UUID shipment = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packing-completion"), "{}")).andExpect(status().isOk())).path("shipmentId").asText());
        List<String> labels = labels(shipment);
        assertThat(labels).hasSize(2);

        String load = openLoad(ghn, shipment).path("loadId").asText();
        scan(load, labels.get(0)).andExpect(status().isOk());
        handOver(load).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_FULLY_LOADED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        labels.get(1) + " (kit part 2/2)")));
        assertThat(shipmentRow(shipment)).containsEntry("status", "READY_TO_DISPATCH");
        dispatch(get(BASE + "/loads/" + load)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedPackageCount").value(2))
                .andExpect(jsonPath("$.data.loadedPackageCount").value(1))
                .andExpect(jsonPath("$.data.shipments[0].packages[1].kit.partNo").value(2))
                .andExpect(jsonPath("$.data.shipments[0].packages[1].kit.partCount").value(2))
                .andExpect(jsonPath("$.data.shipments[0].packages[1].loaded").value(false));

        scan(load, labels.get(1)).andExpect(status().isOk());
        handOver(load).andExpect(status().isOk());
        assertThat(shipmentRow(shipment)).containsEntry("status", "HANDED_OVER");
        assertThat(statusOf(order)).isEqualTo(OrderStatus.SHIPPED);
        JsonNode manifest = data(dispatch(get(BASE + "/loads/" + load + "/manifest")).andExpect(status().isOk()));
        assertThat(manifest.path("packageCount").asInt()).isEqualTo(2);
        assertThat(manifest.path("shipments").get(0).path("packages").findValuesAsText("groupId"))
                .containsOnly(kit.path("kitGroupId").asText());
    }

    /** A kit of the line opened, the unit scanned into part 1, every part sealed; its parts, part 1 first. */
    private List<String> packKit(UUID task, UUID line) throws Exception {
        JsonNode kit = data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/package-kits"),
                "{\"orderLineId\":\"" + line + "\"}")).andExpect(status().isCreated()));
        List<String> parts = new ArrayList<>();
        kit.path("packages").forEach(p -> parts.add(p.path("packageId").asText()));
        as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + parts.get(0) + "/scans"),
                "{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA.code() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        for (String part : parts) {
            as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/packages/" + part + "/seal"),
                    MEASURES)).andExpect(status().isOk());
        }
        return parts;
    }

    @Test
    @DisplayName("review of #155: a kit shipped in part of the order and returned stays whole on its shipment; its unit is "
            + "packed again in a new kit and ships with the rest")
    void aReturnedKitIsPackedAgain() throws Exception {
        stock(2);
        OrderSummary order = order(PaymentTerm.PREPAID, null, 2, 1_000_000);
        UUID task = pickedTask(order);
        UUID line = order.lines().getFirst().lineId();
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        List<String> first = packKit(task, line);
        UUID s1 = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packing-completion"), "{\"partial\":true,\"note\":\"one sofa now\"}")).andExpect(status().isOk()))
                .path("shipmentId").asText());
        String load = openLoad(ghn, s1).path("loadId").asText();
        labels(s1).forEach(code -> {
            try {
                scan(load, code).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        handOver(load).andExpect(status().isOk());
        outcome(s1, "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"REFUSED\"}").andExpect(status().isOk());
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/shipments/" + s1 + "/delivery-outcome"),
                "{\"outcome\":\"RETURNED\"}")).andExpect(status().isOk());

        // The returned kit's unit is the order's to ship again: both sofas are packed anew, each in its own kit.
        as(picker, "WAREHOUSE_STAFF", get(BASE + "/tasks/" + task + "/packing-session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packed").value(0));
        List<String> second = packKit(task, line);
        List<String> third = packKit(task, line);
        UUID s2 = UUID.fromString(data(as(picker, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task
                + "/packing-completion"), "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKED"))).path("shipmentId").asText());
        assertThat(shipmentRow(s1)).containsEntry("status", "RETURNED");
        assertThat(jdbc.queryForList("SELECT shipment_id || '/' || status FROM fulfillment.package WHERE id IN "
                + "(?::uuid, ?::uuid)", String.class, first.get(0), first.get(1))).containsOnly(s1 + "/SEALED");
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                s2)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT kit_group_id) FROM fulfillment.package WHERE shipment_id "
                + "= ?", Integer.class, s2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(pl.quantity), 0) FROM fulfillment.package_line pl JOIN "
                + "fulfillment.package p ON p.id = pl.package_id WHERE p.shipment_id = ?", Integer.class, s2))
                .isEqualTo(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(2);
    }
}
