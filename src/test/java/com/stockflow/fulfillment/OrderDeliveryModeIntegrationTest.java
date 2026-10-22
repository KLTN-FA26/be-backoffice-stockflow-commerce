package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
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
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.payment.internal.service.ReceivableService;
import com.stockflow.support.Await;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LP-1 / LP-2 (lead's decisions 2026-10-22) through the real filter chain, permission aspect and database: the Order
 * Coordinator chooses the delivery mode at release or with {@code PUT /orders/{id}/delivery-mode}, until the first
 * package is opened; a full truckload is never paid on delivery, whichever is changed second; packing copies the mode
 * onto the shipment, and a full-truckload shipment stays off parcel loads (it waits for its load plan, PR E).
 *
 * <p>Its own context, hence its own database: packed orders keep their goods.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.test.context=order-delivery-mode"})
class OrderDeliveryModeIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final String SOFA_BARCODE = "8930000000011";
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String ORDERS = "/api/v1/orders";
    private static final String BASE = "/api/v1/fulfillment";
    /** KIT-FORCE: the sofa ships as 2 boxes; packed in an ordinary package, it needs a reason. */
    private static final String ONE_CARTON = "{\"kitOverrideReason\":\"test: packed as one carton\"}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired CreditHoldService creditHolds;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID admin;
    private UUID sales;
    private UUID managerHcm;
    private UUID pickerHcm;
    private UUID packerHcm;
    private UUID customerUser;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        admin = staff("SYSTEM_ADMIN");
        sales = staff("SALES_STAFF");
        managerHcm = staff("WAREHOUSE_MANAGER", HCM);
        pickerHcm = staff("WAREHOUSE_STAFF", HCM);
        packerHcm = staff("WAREHOUSE_STAFF", HCM);
        customerUser = staff("CUSTOMER");
    }

    @AfterEach
    void cleanUp() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus status = orders.findById(id).orElseThrow().status();
            Long packed = jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE id = ? AND packed_at "
                    + "IS NOT NULL", Long.class, id);
            if (status.canTransitionTo(OrderStatus.CANCELLED) && packed == 0) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            }
        }));
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

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token(user, role)));
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "ORDER_COORDINATOR", request);
    }

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(packerHcm, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private static String mode(String deliveryMode, String note) {
        return "{\"deliveryMode\":\"" + deliveryMode + "\"" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private static String release(String deliveryMode) {
        return "{\"warehouseId\":\"" + HCM + "\"" + (deliveryMode == null ? "" : ",\"deliveryMode\":\"" + deliveryMode
                + "\"") + "}";
    }

    /** A prepaid order of the demo customer for {@code sofas} sofas, paid and CONFIRMED - not released. */
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
        return order;
    }

    /** A deposit order of a new customer whose balance is collected as {@code policy} says, its deposit in: CONFIRMED. */
    private OrderSummary confirmedDepositOrder(DepositBalancePolicy policy) {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.PREPAID,
                new BigDecimal("30"), new BigDecimal("100000000"), 30, "test terms", accountant, null, policy));
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(5_000_000), null)), PaymentTerm.DEPOSIT, null,
                null));
        placed.add(order.orderId());
        BigDecimal deposit = order.total().amount().multiply(new BigDecimal("0.3"));
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), deposit, "VND",
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), null, "deposit", accountant,
                List.of(new ReceivableService.OrderPayment(order.orderId(), deposit))));
        Await.until("deposit confirms the order", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        return order;
    }

    /** OC opens the task for the picker, who picks every line by scan and completes picking. Returns the task. */
    private UUID pickedTask(UUID orderId) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + orderId
                + "\",\"assignedUserId\":\"" + pickerHcm + "\"}")).andExpect(status().isCreated()))
                .path("taskId").asText());
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            as(pickerHcm, "WAREHOUSE_STAFF", body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                    + "/confirmation"), "{\"locationCode\":\"" + line.get("location_code") + "\",\"scannedCode\":\""
                    + line.get("sku") + "\",\"quantity\":" + line.get("allocated_qty") + "}"))
                    .andExpect(status().isOk());
        }
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion"))
                .andExpect(status().isOk());
        return task;
    }

    private UUID openPackage(UUID task) throws Exception {
        return UUID.fromString(data(packer(body(post(BASE + "/tasks/" + task + "/packages"), ONE_CARTON))
                .andExpect(status().isCreated())).path("packageId").asText());
    }

    private void packAll(UUID task, UUID parcel, int sofas) throws Exception {
        for (int i = 0; i < sofas; i++) {
            packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans"), "{\"scanId\":\""
                    + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA_BARCODE + "\",\"quantity\":1}"))
                    .andExpect(status().isOk());
        }
        packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal"),
                "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}")).andExpect(status().isOk());
    }

    private static ErrorCode codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ---- LP-1 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("released as a full truckload: the order says so, its history has a DELIVERY_MODE_CHANGE at "
            + "READY_TO_FULFILL by the coordinator; changed again before packing; the first package locks it (409 "
            + "DELIVERY_MODE_LOCKED); the shipment travels FULL_TRUCKLOAD with no COD and stays off parcel loads")
    void releasedAsFullTruckloadAndPacked() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID id = order.orderId();
        oc(get(ORDERS + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("PARCEL"))
                .andExpect(jsonPath("$.data.deliveryModeLocked").value(false));

        oc(body(post(ORDERS + "/" + id + "/release"), release("FULL_TRUCKLOAD"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY_TO_FULFILL"))
                .andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"))
                .andExpect(jsonPath("$.data.deliveryModeLocked").value(false));
        assertThat(jdbc.queryForMap("SELECT delivery_mode, previous_mode, order_status, changed_by FROM "
                + "ordering.order_delivery_mode_change WHERE order_id = ?", id))
                .containsEntry("delivery_mode", "FULL_TRUCKLOAD").containsEntry("previous_mode", "PARCEL")
                .containsEntry("order_status", "READY_TO_FULFILL").containsEntry("changed_by", coordinator);

        // Back to parcels and to a full truckload again, with a note: two more entries; the same mode records nothing.
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("PARCEL", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("PARCEL"));
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("FULL_TRUCKLOAD", "Khách lấy nguyên xe 5 tấn")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("FULL_TRUCKLOAD", null))).andExpect(status().isOk());
        JsonNode history = data(oc(get(ORDERS + "/" + id + "/history")).andExpect(status().isOk()));
        List<JsonNode> changes = new ArrayList<>();
        history.forEach(entry -> {
            if ("DELIVERY_MODE_CHANGE".equals(entry.path("type").asText())) {
                changes.add(entry);
            }
        });
        assertThat(changes).extracting(entry -> entry.path("deliveryMode").asText())
                .containsExactly("FULL_TRUCKLOAD", "PARCEL", "FULL_TRUCKLOAD");
        assertThat(changes.get(2).path("previousDeliveryMode").asText()).isEqualTo("PARCEL");
        assertThat(changes.get(2).path("reason").asText()).isEqualTo("Khách lấy nguyên xe 5 tấn");
        assertThat(changes.get(2).hasNonNull("from")).isFalse();

        // Picking does not lock it; the first package does.
        UUID task = pickedTask(id);
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("FULL_TRUCKLOAD", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryModeLocked").value(false));
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID parcel = openPackage(task);
        assertThat(jdbc.queryForObject("SELECT packing_started_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, id)).isTrue();
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("PARCEL", null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_MODE_LOCKED"));
        oc(get(ORDERS + "/" + id)).andExpect(jsonPath("$.data.deliveryModeLocked").value(true))
                .andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_delivery_mode_change WHERE order_id = ?",
                Integer.class, id)).isEqualTo(3);

        // Packing copies the mode onto the shipment.
        packAll(task, parcel, 1);
        String shipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk()))
                .path("shipmentId").asText();
        assertThat(jdbc.queryForMap("SELECT delivery_mode, cod_amount, status FROM fulfillment.shipment WHERE id = ?::uuid",
                shipment)).containsEntry("delivery_mode", "FULL_TRUCKLOAD").containsEntry("status", "READY_TO_DISPATCH")
                .satisfies(row -> assertThat((BigDecimal) row.get("cod_amount")).isZero());

        // Dispatch (#137): a full-truckload shipment never goes on a parcel load - it waits for its load plan (PR E).
        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        as(managerHcm, "WAREHOUSE_MANAGER", body(post(BASE + "/loads"), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + ghn + "\",\"shipmentIds\":[\"" + shipment
                + "\"]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_REQUIRES_LOAD_PLAN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE shipment_id = "
                + "?::uuid", Integer.class, shipment)).isZero();
    }

    @Test
    @DisplayName("released without a mode: PARCEL, nothing recorded; packing makes a PARCEL shipment as before")
    void releasedAsParcelByDefault() throws Exception {
        OrderSummary order = confirmedOrder(1);
        oc(body(post(ORDERS + "/" + order.orderId() + "/release"), release(null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("PARCEL"));
        oc(body(post(ORDERS + "/" + order.orderId() + "/release"), release(null))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_delivery_mode_change WHERE order_id = ?",
                Integer.class, order.orderId())).isZero();
        UUID task = pickedTask(order.orderId());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        packAll(task, openPackage(task), 1);
        String shipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk()))
                .path("shipmentId").asText();
        assertThat(jdbc.queryForObject("SELECT delivery_mode FROM fulfillment.shipment WHERE id = ?::uuid", String.class,
                shipment)).isEqualTo("PARCEL");
    }

    // ---- LP-2 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a deposit order paid on delivery: FULL_TRUCKLOAD is 409 DELIVERY_MODE_FTL_NO_COD by PUT and at "
            + "release - where nothing is released - and the database refuses it too; BEFORE_DELIVERY may")
    void fullTruckloadNeverCollectsOnDelivery() throws Exception {
        OrderSummary cod = confirmedDepositOrder(DepositBalancePolicy.ON_DELIVERY);
        oc(body(put(ORDERS + "/" + cod.orderId() + "/delivery-mode"), mode("FULL_TRUCKLOAD", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DELIVERY_MODE_FTL_NO_COD"));
        oc(body(post(ORDERS + "/" + cod.orderId() + "/release"), release("FULL_TRUCKLOAD")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DELIVERY_MODE_FTL_NO_COD"));
        OrderSummary after = orders.findById(cod.orderId()).orElseThrow();
        assertThat(after.status()).as("nothing released").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.warehouseId()).isNull();
        assertThat(after.lifecycle().deliveryMode()).isEqualTo(com.stockflow.order.api.DeliveryMode.PARCEL);
        Throwable byHand = catchThrowable(() -> jdbc.update(
                "UPDATE ordering.customer_order SET delivery_mode = 'FULL_TRUCKLOAD' WHERE id = ?", cod.orderId()));
        assertThat(byHand).hasMessageContaining("ck_order_ftl_no_cod");
        oc(body(post(ORDERS + "/" + cod.orderId() + "/release"), release("PARCEL"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("PARCEL"));

        OrderSummary before = confirmedDepositOrder(DepositBalancePolicy.BEFORE_DELIVERY);
        oc(body(post(ORDERS + "/" + before.orderId() + "/release"), release("FULL_TRUCKLOAD")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
    }

    /** A CREDIT order over its new customer's limit - ON_HOLD for credit - whose deposit default is ON_DELIVERY. */
    private OrderSummary creditHeldOrderOfCodCustomer(UUID accountant) {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.CREDIT,
                new BigDecimal("30"), new BigDecimal("1000000"), 30, "test terms", accountant, null,
                DepositBalancePolicy.ON_DELIVERY));
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(5_000_000), null)), PaymentTerm.CREDIT, null));
        placed.add(order.orderId());
        assertThat(order.status()).as("over its credit limit").isEqualTo(OrderStatus.ON_HOLD);
        return order;
    }

    @Test
    @DisplayName("the other way round (review of #157): a full-truckload order on credit hold switched to a deposit takes "
            + "BEFORE_DELIVERY, not its customer's ON_DELIVERY default, and the decision's note says why; a parcel order "
            + "keeps the default")
    void creditRefusalOnFullTruckloadPaysBeforeDelivery() throws Exception {
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        OrderSummary ftl = creditHeldOrderOfCodCustomer(accountant);
        oc(body(put(ORDERS + "/" + ftl.orderId() + "/delivery-mode"), mode("FULL_TRUCKLOAD", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));

        OrderSummary switched = creditHolds.refuse(ftl.orderId(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT,
                null, "khách trả cọc", accountant);
        assertThat(switched.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(switched.paymentTerm()).isEqualTo(PaymentTerm.DEPOSIT);
        assertThat(switched.payment().balancePolicy()).isEqualTo(com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY);
        assertThat(switched.lifecycle().deliveryMode()).isEqualTo(com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD);
        assertThat(jdbc.queryForObject("""
                SELECT note FROM ordering.order_credit_check WHERE order_id = ? AND outcome = 'REJECTED'
                ORDER BY checked_at DESC, created_at DESC LIMIT 1""", String.class, ftl.orderId()))
                .startsWith("khách trả cọc").contains("balance before delivery").contains("LP-2");

        OrderSummary parcel = creditHeldOrderOfCodCustomer(accountant);
        OrderSummary parcelSwitched = creditHolds.refuse(parcel.orderId(),
                CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "khách trả cọc", accountant);
        assertThat(parcelSwitched.payment().balancePolicy())
                .as("the customer's default stands on a parcel order")
                .isEqualTo(com.stockflow.order.api.BalancePolicy.ON_DELIVERY);
        assertThat(jdbc.queryForObject("""
                SELECT note FROM ordering.order_credit_check WHERE order_id = ? AND outcome = 'REJECTED'
                ORDER BY checked_at DESC, created_at DESC LIMIT 1""", String.class, parcel.orderId()))
                .isEqualTo("khách trả cọc");
        // ON_DELIVERY chosen explicitly for a full-truckload order stays 409 (OrderDeliveryModeTest.codRefusedForFtl).
    }

    @Test
    @DisplayName("release with a mode (review of #157): decided under the order's lock against the mode it has then - "
            + "an approver without sales-order-delivery-modes:UPDATE may keep the mode a coordinator just chose but "
            + "not undo it (403, nothing released); the coordinator may")
    void releaseModeDecidedUnderTheLock() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID id = order.orderId();
        // A coordinator chose a full truckload a moment before the release.
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("FULL_TRUCKLOAD", null))).andExpect(status().isOk());
        com.stockflow.common.security.CurrentUser approver = new com.stockflow.common.security.CurrentUser(sales,
                "approver", java.util.Set.of(), java.util.Set.of(com.stockflow.common.security.PermissionCode.of(
                        "sales-orders", com.stockflow.common.security.Action.APPROVE)), DataScope.ALL,
                java.util.Set.of());

        Throwable refused = catchThrowable(() -> releases.release(id, HCM,
                com.stockflow.order.api.DeliveryMode.PARCEL, approver));
        assertThat(refused).isInstanceOf(com.stockflow.common.security.PermissionDeniedException.class);
        OrderSummary after = orders.findById(id).orElseThrow();
        assertThat(after.status()).as("nothing released").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.lifecycle().deliveryMode()).isEqualTo(com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD);

        OrderSummary released = releases.release(id, HCM, com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD,
                approver);
        assertThat(released.status()).as("keeping the order's mode needs nothing more")
                .isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(released.lifecycle().deliveryMode()).isEqualTo(com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_delivery_mode_change WHERE order_id = ?",
                Integer.class, id)).as("only the coordinator's change").isEqualTo(1);

        // Through HTTP: the coordinator holds both permissions and changes it with the release.
        OrderSummary other = confirmedOrder(1);
        oc(body(post(ORDERS + "/" + other.orderId() + "/release"), release("FULL_TRUCKLOAD"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
    }

    // ---- who may ---------------------------------------------------------------------------------

    @Test
    @DisplayName("PUT delivery-mode: the coordinator and the system admin; Sales, the warehouse and a customer 403; an "
            + "unknown order 404; a missing mode 400")
    void whoMayChangeIt() throws Exception {
        OrderSummary order = confirmedOrder(1);
        String url = ORDERS + "/" + order.orderId() + "/delivery-mode";
        as(sales, "SALES_STAFF", body(put(url), mode("FULL_TRUCKLOAD", null))).andExpect(status().isForbidden());
        as(managerHcm, "WAREHOUSE_MANAGER", body(put(url), mode("FULL_TRUCKLOAD", null)))
                .andExpect(status().isForbidden());
        as(customerUser, "CUSTOMER", body(put(url), mode("FULL_TRUCKLOAD", null))).andExpect(status().isForbidden());
        oc(body(put(url), "{\"note\":\"x\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        oc(body(put(url), mode("TRUCK", null))).andExpect(status().isBadRequest());
        oc(body(put(ORDERS + "/" + UUID.randomUUID() + "/delivery-mode"), mode("PARCEL", null)))
                .andExpect(status().isNotFound());
        as(admin, "SYSTEM_ADMIN", body(put(url), mode("FULL_TRUCKLOAD", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
        assertThat(jdbc.queryForObject("SELECT order_status FROM ordering.order_delivery_mode_change WHERE order_id = ?",
                String.class, order.orderId())).as("chosen before release").isEqualTo("CONFIRMED");
        assertThat(orders.findById(order.orderId()).orElseThrow().lifecycle().deliveryMode())
                .isEqualTo(com.stockflow.order.api.DeliveryMode.FULL_TRUCKLOAD);
    }

    @Test
    @DisplayName("LP-1 with a kit (review of #155): a kit opened as the first package starts packing - the delivery mode "
            + "is locked (409 DELIVERY_MODE_LOCKED)")
    void aKitOpenedFirstLocksTheDeliveryMode() throws Exception {
        OrderSummary order = confirmedOrder(1);
        UUID id = order.orderId();
        oc(body(post(ORDERS + "/" + id + "/release"), release("FULL_TRUCKLOAD"))).andExpect(status().isOk());
        UUID task = pickedTask(id);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("FULL_TRUCKLOAD", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryModeLocked").value(false));
        UUID line = order.lines().getFirst().lineId();
        packer(body(post(BASE + "/tasks/" + task + "/package-kits"), "{\"orderLineId\":\"" + line + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.packages.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT packing_started_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, id)).isTrue();
        oc(body(put(ORDERS + "/" + id + "/delivery-mode"), mode("PARCEL", null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_MODE_LOCKED"));
        oc(get(ORDERS + "/" + id)).andExpect(jsonPath("$.data.deliveryModeLocked").value(true))
                .andExpect(jsonPath("$.data.deliveryMode").value("FULL_TRUCKLOAD"));
    }
}
