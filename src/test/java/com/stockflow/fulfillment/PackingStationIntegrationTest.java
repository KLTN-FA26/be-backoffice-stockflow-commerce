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
import com.stockflow.order.internal.service.OrderReleases;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G4-5 (SCRUM-278/281/282) through the real filter chain, permission aspect, warehouse scope and database: the packing
 * queue, scan-to-verify (wrong item, over-scan, lot), packages with weight and size, the label, completing into a
 * shipment READY_TO_DISPATCH and the order PACKED; two packers on one order; undoing a package; the deposit order's
 * payment warning and the COD amount on the label (kltn-docs 08, 09 §5, BR-04).
 *
 * <p>Its own context, hence its own database: packed orders cannot be cancelled, so their stock does not come back,
 * and the shared demo stock of the other suites is left alone.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000"})
class PackingStationIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Sku TABLE = new Sku("TABLE-OAK-160");
    private static final String SOFA_BARCODE = "8930000000011";
    private static final String TABLE_LOT = "LOT-2026-03";
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";

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

    private UUID coordinator;
    private UUID managerHcm;
    private UUID pickerHcm;
    private UUID packerHcm;
    private UUID otherPackerHcm;
    private UUID packerElsewhere;
    private UUID qcHcm;
    private UUID customerUser;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        managerHcm = staff("WAREHOUSE_MANAGER", HCM);
        pickerHcm = staff("WAREHOUSE_STAFF", HCM);
        packerHcm = staff("WAREHOUSE_STAFF", HCM);
        otherPackerHcm = staff("WAREHOUSE_STAFF", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        packerElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        qcHcm = staff("QC_STAFF", HCM);
        customerUser = staff("CUSTOMER");
    }

    /** Orders not packed are cancelled, so their holds go back; a packed one keeps its goods (it ships). */
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

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "P" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
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

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(packerHcm, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** A paid order of the demo customer (prepaid), released at HCM. */
    private OrderSummary paidOrder(PlaceOrderCommand.Line... lines) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(lines)));
        placed.add(order.orderId());
        // Split shipments throughout: Sales allowed them on the order (PACK-PARTIAL, lead's decision 2026-10-21).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
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

    /**
     * A deposit order of a customer whose balance is collected as {@code policy} says, its 30% deposit received through
     * the money ledger (G2-3), released at HCM.
     */
    private OrderSummary depositOrder(DepositBalancePolicy policy, int sofas) {
        return depositOrder(policy, sofas, 5_000_000);
    }

    private OrderSummary depositOrder(DepositBalancePolicy policy, int sofas, long price) {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.PREPAID,
                new BigDecimal("30"), new BigDecimal("100000000"), 30, "test terms", accountant, null, policy));
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, sofas, Money.vnd(price), null)), PaymentTerm.DEPOSIT, null,
                null));
        placed.add(order.orderId());
        // Split shipments throughout: Sales allowed them on the order (PACK-PARTIAL, lead's decision 2026-10-21).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        BigDecimal deposit = order.total().amount().multiply(new BigDecimal("0.3"));
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), deposit, "VND",
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), null, "deposit", accountant,
                List.of(new ReceivableService.OrderPayment(order.orderId(), deposit))));
        Await.until("deposit confirms the order", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        return releases.release(order.orderId(), HCM, coordinator);
    }

    private static PlaceOrderCommand.Line sofa(int quantity) {
        return new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null);
    }

    private static PlaceOrderCommand.Line table(int quantity) {
        return new PlaceOrderCommand.Line(TABLE, quantity, Money.vnd(3_000_000), null);
    }

    /** OC opens the task for the picker, who picks every line by scan and completes picking. Returns the task. */
    private UUID pickedTask(OrderSummary order) throws Exception {
        UUID task = UUID.fromString(data(as(coordinator, "ORDER_COORDINATOR", post(BASE + "/tasks")
                .contentType("application/json").content("{\"orderId\":\"" + order.orderId()
                        + "\",\"assignedUserId\":\"" + pickerHcm + "\"}"))
                .andExpect(status().isCreated())).path("taskId").asText());
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                    + "/confirmation").contentType("application/json").content("{\"locationCode\":\""
                    + line.get("location_code") + "\",\"scannedCode\":\"" + line.get("sku") + "\",\"quantity\":"
                    + line.get("allocated_qty") + "}")).andExpect(status().isOk());
        }
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        return task;
    }

    private UUID openPackage(UUID task) throws Exception {
        return UUID.fromString(data(packer(post(BASE + "/tasks/" + task + "/packages").contentType("application/json")
                .content("{}")).andExpect(status().isCreated())).path("packageId").asText());
    }

    private static String scan(UUID scanId, String code, String lot, int quantity) {
        return "{\"scanId\":\"" + scanId + "\",\"scannedCode\":\"" + code + "\","
                + (lot == null ? "" : "\"lotNumber\":\"" + lot + "\",") + "\"quantity\":" + quantity + "}";
    }

    private ResultActions scanAs(UUID user, UUID task, UUID parcel, String body) throws Exception {
        return as(user, user.equals(managerHcm) ? "WAREHOUSE_MANAGER" : "WAREHOUSE_STAFF",
                post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans").contentType("application/json")
                        .content(body));
    }

    private ResultActions seal(UUID task, UUID parcel, String body) throws Exception {
        return packer(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal").contentType("application/json")
                .content(body));
    }

    private static final String MEASURES = "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}";

    private int sum(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---- the whole flow ------------------------------------------------------------------------

    @Test
    @DisplayName("stock order: queue, scan-to-verify with every refusal, two packages sealed with weight and size, label, "
            + "shipment READY_TO_DISPATCH, order PACKED, idempotent completion, no cancellation afterwards")
    void aStockOrderShipsInTwoPackages() throws Exception {
        OrderSummary order = paidOrder(sofa(2), table(1));
        UUID sofaLine = order.lines().stream().filter(l -> l.sku().equals(SOFA.code())).findFirst().orElseThrow().lineId();
        UUID task = pickedTask(order);

        // SCRUM-281: the picked order waits in the queue of its warehouse.
        JsonNode queue = data(packer(get(BASE + "/packing-queue?warehouseId=" + HCM + "&size=200"))
                .andExpect(status().isOk()));
        assertThat(queue.path("items").findValuesAsText("taskId")).contains(task.toString());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKING"))
                .andExpect(jsonPath("$.data.packerId").value(packerHcm.toString()))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.paymentDue").doesNotExist());
        UUID first = openPackage(task);

        // SCRUM-282: every wrong scan is refused with its own code and writes nothing.
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), "NOT-IN-ORDER", null, 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_ITEM_NOT_IN_ORDER"));
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), SOFA_BARCODE, null, 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_QUANTITY_EXCEEDS_ORDER"));
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), TABLE.code(), "LOT-2026-04", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_LOT_MISMATCH"));
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), TABLE.code(), null, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lotNumber"));
        assertThat(sum("SELECT count(*) FROM fulfillment.package_scan WHERE package_id = ?", first)).isZero();

        // The right scan, by barcode; the same scan again changes nothing.
        UUID scanId = UUID.randomUUID();
        scanAs(packerHcm, task, first, scan(scanId, SOFA_BARCODE, null, 1)).andExpect(status().isOk());
        scanAs(packerHcm, task, first, scan(scanId, SOFA_BARCODE, null, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.sku == 'SOFA-3S-GREY')].packed").value(1));
        UUID second = openPackage(task);
        scanAs(packerHcm, task, second, scan(UUID.randomUUID(), "sofa-3s-grey", null, 1)).andExpect(status().isOk());
        scanAs(packerHcm, task, second, scan(UUID.randomUUID(), TABLE.code(), TABLE_LOT, 1)).andExpect(status().isOk());

        // A package with goods but no measures: sealing without its height is 400; completing is refused.
        seal(task, first, "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        seal(task, first, MEASURES).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SEALED"))
                .andExpect(jsonPath("$.data.labelCode").value("PKG-" + order.orderNumber() + "-1"))
                .andExpect(jsonPath("$.data.dimWeightKg").value(org.hamcrest.Matchers.closeTo(306.375, 0.0001)));
        packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_SEALED"));
        seal(task, second, "{\"weightKg\":40,\"lengthCm\":165,\"widthCm\":85,\"heightCm\":20,\"rotatable\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rotatableOverridden").value(true));

        // The label before completion is provisional: n of the sealed packages that would ship now.
        packer(get(BASE + "/tasks/" + task + "/packages/" + second + "/label")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provisional").value(true))
                .andExpect(jsonPath("$.data.packageNo").value(2)).andExpect(jsonPath("$.data.packageCount").value(2))
                .andExpect(jsonPath("$.data.codAmount").value(0))
                .andExpect(jsonPath("$.data.rotatable").value(false));

        JsonNode done = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKED")));
        String shipment = done.path("shipmentId").asText();
        assertThat(shipment).isNotBlank();
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(jdbc.queryForMap("""
                SELECT status, package_count, partial, code, warehouse_id, delivery_mode, ready_by FROM fulfillment.shipment
                 WHERE id = ?::uuid""", shipment))
                .containsEntry("status", "READY_TO_DISPATCH").containsEntry("package_count", 2)
                .containsEntry("partial", false).containsEntry("code", order.orderNumber() + "-S1")
                .containsEntry("warehouse_id", HCM).containsEntry("delivery_mode", "PARCEL")
                .containsEntry("ready_by", packerHcm);
        assertThat(sum("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ?::uuid "
                + "AND status = 'READY_TO_DISPATCH' AND recorded_by = ?", shipment, packerHcm)).isEqualTo(1);
        assertThat(sum("SELECT COALESCE(SUM(quantity), 0) FROM fulfillment.package_line WHERE order_line_id = ?",
                sofaLine)).isEqualTo(2);
        packer(get(BASE + "/tasks/" + task + "/packages/" + first + "/label")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provisional").value(false))
                .andExpect(jsonPath("$.data.shipmentCode").value(order.orderNumber() + "-S1"))
                .andExpect(jsonPath("$.data.packageNo").value(1)).andExpect(jsonPath("$.data.packageCount").value(2))
                .andExpect(jsonPath("$.data.returnAddress").isNotEmpty())
                .andExpect(jsonPath("$.data.orderNumber").value(order.orderNumber()));

        // Completed again (a retry): 200, the same shipment, nothing new.
        packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipmentId").value(shipment));
        assertThat(sum("SELECT count(*) FROM fulfillment.shipment WHERE order_id = ?", order.orderId())).isEqualTo(1);
        // Packed: no more scans, and the order is not cancelled any more (decision 2026-10-17).
        scanAs(packerHcm, task, second, scan(UUID.randomUUID(), SOFA.code(), null, 1))
                .andExpect(status().isConflict());
        Throwable refused = catchThrowable(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> orders.cancel(order.orderId(), new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                        "too late", null, null))));
        assertThat(((BusinessException) refused).errorCode()).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = "
                + "'fulfillment-package' AND outcome = 'SUCCESS' AND resource_id = ?", Integer.class, first.toString()))
                .isPositive();
    }

    // ---- who may do it ---------------------------------------------------------------------------

    @Test
    @DisplayName("before picking ends 409 PACKING_NOT_READY; OC and customers 403; another warehouse OUT_OF_DATA_SCOPE; "
            + "QC reads the queue; a second packer 409 PACKING_TAKEN; a manager steps in")
    void whoMayPack() throws Exception {
        OrderSummary order = paidOrder(sofa(1));
        UUID task = UUID.fromString(data(as(coordinator, "ORDER_COORDINATOR", post(BASE + "/tasks")
                .contentType("application/json").content("{\"orderId\":\"" + order.orderId()
                        + "\",\"assignedUserId\":\"" + pickerHcm + "\"}"))
                .andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKING_NOT_READY"));
        // (the task is picked by the fixture below; a second task cannot exist for one order, so pick this one)
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT id, location_code FROM fulfillment.pick_line WHERE pick_id = ?", task);
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                + "/confirmation").contentType("application/json").content("{\"locationCode\":\""
                + line.get("location_code") + "\",\"scannedCode\":\"SOFA-3S-GREY\",\"quantity\":1}"))
                .andExpect(status().isOk());
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion"))
                .andExpect(status().isOk());

        as(coordinator, "ORDER_COORDINATOR", post(BASE + "/tasks/" + task + "/packing-start"))
                .andExpect(status().isForbidden());
        as(customerUser, "CUSTOMER", get(BASE + "/packing-queue")).andExpect(status().isForbidden());
        as(qcHcm, "QC_STAFF", get(BASE + "/packing-queue")).andExpect(status().isOk());
        as(packerElsewhere, "WAREHOUSE_STAFF", get(BASE + "/tasks/" + task + "/packing-session"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(packerElsewhere, "WAREHOUSE_STAFF", get(BASE + "/packing-queue?warehouseId=" + HCM))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        JsonNode elsewhere = data(as(packerElsewhere, "WAREHOUSE_STAFF", get(BASE + "/packing-queue"))
                .andExpect(status().isOk()));
        assertThat(elsewhere.path("items").findValuesAsText("taskId")).doesNotContain(task.toString());

        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        as(otherPackerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packing-start"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        as(otherPackerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/packages").contentType("application/json")
                .content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));
        as(managerHcm, "WAREHOUSE_MANAGER", post(BASE + "/tasks/" + task + "/packages").contentType("application/json")
                .content("{\"packageType\":\"BAG\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.packageType").value("BAG"));
        as(coordinator, "ORDER_COORDINATOR", get(BASE + "/tasks/" + task + "/packing-session"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("two packers scanning the same line at once: serialised on the task, the order line is never over-packed")
    void twoPackersAtOnce() throws Exception {
        OrderSummary order = paidOrder(sofa(2));
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID parcel = openPackage(task);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (UUID who : List.of(packerHcm, managerHcm)) {
                String body = scan(UUID.randomUUID(), SOFA.code(), null, 2);
                String header = token(who, who.equals(managerHcm) ? "WAREHOUSE_MANAGER" : "WAREHOUSE_STAFF");
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans")
                            .header("Authorization", header).contentType("application/json").content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(sum("SELECT COALESCE(SUM(quantity), 0) FROM fulfillment.package_line WHERE package_id = ?", parcel))
                .isEqualTo(2);
        assertThat(sum("SELECT COALESCE(SUM(quantity), 0) FROM fulfillment.package_scan WHERE package_id = ?", parcel))
                .isEqualTo(2);
    }

    // ---- undo ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("before completion: units taken out, a sealed package re-opened, a package voided - its goods count as "
            + "unpacked and the voided label is void; nothing sealed, nothing ships")
    void undoBeforeCompletion() throws Exception {
        OrderSummary order = paidOrder(sofa(2));
        UUID sofaLine = order.lines().getFirst().lineId();
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID parcel = openPackage(task);
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, 2)).andExpect(status().isOk());
        packer(delete(BASE + "/tasks/" + task + "/packages/" + parcel + "/lines/" + sofaLine + "?quantity=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packed").value(1));
        seal(task, parcel, MEASURES).andExpect(status().isOk());
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKAGE_SEALED"));
        packer(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/reopening")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        packer(delete(BASE + "/tasks/" + task + "/packages/" + parcel)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packed").value(0))
                .andExpect(jsonPath("$.data.packages[0].status").value("VOIDED"));
        packer(get(BASE + "/tasks/" + task + "/packages/" + parcel + "/label")).andExpect(status().isConflict());
        packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_NOTHING_TO_SHIP"));
        assertThat(sum("SELECT count(*) FROM fulfillment.package_scan WHERE package_id = ?", parcel)).isEqualTo(2);
        // A new package takes the next number; the voided one keeps its own.
        UUID next = openPackage(task);
        assertThat(jdbc.queryForObject("SELECT package_no FROM fulfillment.package WHERE id = ?", Integer.class, next))
                .isEqualTo(2);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
    }

    // ---- money ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("G3-Q4: a deposit order paid before delivery shows what is still due at packing (session and "
            + "completion) - packing is not blocked, the handover will be")
    void aDepositOrderShowsPaymentDueAtPacking() throws Exception {
        OrderSummary order = depositOrder(DepositBalancePolicy.BEFORE_DELIVERY, 2);       // 10M, deposit 3M
        UUID task = pickedTask(order);
        JsonNode session = data(packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentDue.currency").value("VND")));
        assertThat(session.path("paymentDue").path("amountDueBeforeHandover").decimalValue())
                .isEqualByComparingTo("7000000");
        UUID parcel = openPackage(task);
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, 2)).andExpect(status().isOk());
        seal(task, parcel, MEASURES).andExpect(status().isOk());
        JsonNode packed = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKED")));
        assertThat(packed.path("paymentDue").path("amountDueBeforeHandover").decimalValue())
                .isEqualByComparingTo("7000000");
        assertThat(packed.path("shipmentId").asText()).isNotBlank();
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
    }

    @Test
    @DisplayName("09 BR-04: an order whose carrier collects the balance prints it on the label, split per shipment; a "
            + "partial shipment needs a reason")
    void theLabelCarriesTheCodAmount() throws Exception {
        OrderSummary order = depositOrder(DepositBalancePolicy.ON_DELIVERY, 2);            // 10M, deposit 3M, COD 7M
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentDue").doesNotExist());
        UUID first = openPackage(task);
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
        seal(task, first, MEASURES).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-completion").contentType("application/json")
                .content("{\"partial\":true}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        String requestId = UUID.randomUUID().toString();
        String partial = "{\"requestId\":\"" + requestId + "\",\"partial\":true,\"note\":\"Customer wants one now\"}";
        String firstShipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")
                .contentType("application/json").content(partial)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKING"))).path("shipmentId").asText();
        packer(post(BASE + "/tasks/" + task + "/packing-completion").contentType("application/json").content(partial))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shipmentId").value(firstShipment));
        // 5M of goods, 3M paid ahead: the carrier collects 2M for the first shipment, 5M for the second.
        JsonNode label = data(packer(get(BASE + "/tasks/" + task + "/packages/" + first + "/label"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.packageCount").value(1)));
        assertThat(label.path("codAmount").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(jdbc.queryForObject("SELECT cod_amount::bigint FROM fulfillment.shipment WHERE id = ?::uuid",
                Long.class, firstShipment)).isEqualTo(2_000_000L);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("part packed: still picking")
                .isEqualTo(OrderStatus.PICKING);
    }

    // ---- review of G4-5 ---------------------------------------------------------------------------------

    private long cod(String shipmentId) {
        return jdbc.queryForObject("SELECT cod_amount::bigint FROM fulfillment.shipment WHERE id = ?::uuid", Long.class,
                shipmentId);
    }

    @Test
    @DisplayName("review #2: 10M ON_DELIVERY, 3M ahead - S1 4M carries COD 1M; S2 6M completed before S1 is handed over "
            + "carries 6M (the advance is S1's), on the shipments and on the labels")
    void theAdvanceIsCountedOnceAcrossWaitingShipments() throws Exception {
        OrderSummary order = depositOrder(DepositBalancePolicy.ON_DELIVERY, 10, 1_000_000);
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID first = openPackage(task);
        scanAs(packerHcm, task, first, scan(UUID.randomUUID(), SOFA.code(), null, 4)).andExpect(status().isOk());
        seal(task, first, MEASURES).andExpect(status().isOk());
        String s1 = data(packer(post(BASE + "/tasks/" + task + "/packing-completion").contentType("application/json")
                .content("{\"partial\":true,\"note\":\"four now\"}")).andExpect(status().isOk())).path("shipmentId")
                .asText();
        UUID second = openPackage(task);
        scanAs(packerHcm, task, second, scan(UUID.randomUUID(), SOFA.code(), null, 6)).andExpect(status().isOk());
        seal(task, second, MEASURES).andExpect(status().isOk());
        String s2 = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk()))
                .path("shipmentId").asText();

        assertThat(cod(s1)).isEqualTo(1_000_000L);
        assertThat(cod(s2)).isEqualTo(6_000_000L);
        assertThat(data(packer(get(BASE + "/tasks/" + task + "/packages/" + first + "/label"))).path("codAmount")
                .decimalValue()).isEqualByComparingTo("1000000");
        assertThat(data(packer(get(BASE + "/tasks/" + task + "/packages/" + second + "/label"))).path("codAmount")
                .decimalValue()).isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("review #3/#6: a removal retried with its scanId removes once; a scan retried after the package was "
            + "sealed answers 200; a scanId reused on another task is 409 PACK_SCAN_ID_CONFLICT; huge quantities 400")
    void retriesAreAnsweredFirst() throws Exception {
        OrderSummary order = paidOrder(sofa(2));
        UUID sofaLine = order.lines().getFirst().lineId();
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID parcel = openPackage(task);
        UUID scanned = UUID.randomUUID();
        scanAs(packerHcm, task, parcel, scan(scanned, SOFA.code(), null, 2)).andExpect(status().isOk());
        UUID removal = UUID.randomUUID();
        String remove = BASE + "/tasks/" + task + "/packages/" + parcel + "/lines/" + sofaLine + "?quantity=1&scanId="
                + removal;
        packer(delete(remove)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].packed").value(1));
        packer(delete(remove)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].packed").value(1));
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, 1_000_001))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, Integer.MAX_VALUE))
                .andExpect(status().isBadRequest());
        packer(delete(BASE + "/tasks/" + task + "/packages/" + parcel + "/lines/" + sofaLine + "?quantity=2147483647"))
                .andExpect(status().isBadRequest());
        seal(task, parcel, MEASURES).andExpect(status().isOk());

        scanAs(packerHcm, task, parcel, scan(scanned, SOFA.code(), null, 2)) // a retry after sealing
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].packed").value(1));
        assertThat(sum("SELECT count(*) FROM fulfillment.package_scan WHERE package_id = ?", parcel)).isEqualTo(2);

        OrderSummary other = paidOrder(sofa(1));
        UUID otherTask = pickedTask(other);
        packer(post(BASE + "/tasks/" + otherTask + "/packing-start")).andExpect(status().isOk());
        UUID otherParcel = openPackage(otherTask);
        scanAs(packerHcm, otherTask, otherParcel, scan(scanned, SOFA.code(), null, 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_SCAN_ID_CONFLICT"));
        assertThat(sum("SELECT count(*) FROM fulfillment.package_scan WHERE package_id = ?", otherParcel)).isZero();
    }

    @Test
    @DisplayName("review #4: an order held for anything but its design check leaves the packing queue and cannot be "
            + "packed; resolved, it is back")
    void heldOrdersLeaveTheQueue() throws Exception {
        OrderSummary order = paidOrder(sofa(1));
        UUID task = pickedTask(order);
        String queue = BASE + "/packing-queue?warehouseId=" + HCM + "&size=200";
        assertThat(data(packer(get(queue))).path("items").findValuesAsText("taskId")).contains(task.toString());

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.putOnHold(order.orderId(),
                com.stockflow.order.api.HoldReason.ADDRESS, "Ward missing", coordinator));

        assertThat(data(packer(get(queue))).path("items").findValuesAsText("taskId")).doesNotContain(task.toString());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.resolveHold(order.orderId(), "Fixed",
                coordinator));
        assertThat(data(packer(get(queue))).path("items").findValuesAsText("taskId")).contains(task.toString());
    }

    @Test
    @DisplayName("FE #58: the packer, not the picker, reads the task once picking is done; not before; staff of another "
            + "warehouse are refused; the session's shipments carry the COD currency")
    void thePackerReadsTheTask() throws Exception {
        OrderSummary order = paidOrder(sofa(1));
        UUID task = UUID.fromString(data(as(coordinator, "ORDER_COORDINATOR", post(BASE + "/tasks")
                .contentType("application/json").content("{\"orderId\":\"" + order.orderId()
                        + "\",\"assignedUserId\":\"" + pickerHcm + "\"}"))
                .andExpect(status().isCreated())).path("taskId").asText());
        packer(get(BASE + "/tasks/" + task)).andExpect(status().isNotFound());
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT id, location_code FROM fulfillment.pick_line WHERE pick_id = ?", task);
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id")
                + "/confirmation").contentType("application/json").content("{\"locationCode\":\""
                + line.get("location_code") + "\",\"scannedCode\":\"SOFA-3S-GREY\",\"quantity\":1}"))
                .andExpect(status().isOk());
        as(pickerHcm, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-completion"))
                .andExpect(status().isOk());

        packer(get(BASE + "/tasks/" + task)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.data.lines[0].sku").value("SOFA-3S-GREY"));
        as(packerElsewhere, "WAREHOUSE_STAFF", get(BASE + "/tasks/" + task)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));

        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID parcel = openPackage(task);
        scanAs(packerHcm, task, parcel, scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
        seal(task, parcel, MEASURES).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk());
        packer(get(BASE + "/tasks/" + task + "/packing-session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments[0].currency").value("VND"))
                .andExpect(jsonPath("$.data.shipments[0].codAmount").value(0));
    }

    // ---- kits ("bộ", SCRUM-287 PR K, LP2-5/LP2-6) -------------------------------------------------------------

    /** Brings its own sofas, picked first (oldest): the class shares the demo stock with the tests above. */
    private void stock(int sofas) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                    created_at, received_at)
                VALUES (?, ?, 'HCM-A02-1-A', ?, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '400 days')""",
                Identifiers.newId(), SOFA.code(), sofas));
    }

    private ResultActions openKit(UUID task, String body) throws Exception {
        return packer(post(BASE + "/tasks/" + task + "/package-kits").contentType("application/json").content(body));
    }

    /** The parts of a kit of the line just opened, part 1 first. */
    private List<UUID> kit(UUID task, UUID line) throws Exception {
        JsonNode kit = data(openKit(task, "{\"orderLineId\":\"" + line + "\"}").andExpect(status().isCreated()));
        List<UUID> parts = new ArrayList<>();
        kit.path("packages").forEach(p -> parts.add(UUID.fromString(p.path("packageId").asText())));
        return parts;
    }

    private UUID lineOf(OrderSummary order, Sku sku) {
        return order.lines().stream().filter(l -> l.sku().equals(sku.code())).findFirst().orElseThrow().lineId();
    }

    @Test
    @DisplayName("LP2-6 a kit of 2 (sofa A+B) on a deposit order: opened at once, only part 1 takes the unit, every part "
            + "sealed with its own size; an incomplete kit blocks completion, partial or not (KIT_INCOMPLETE); voiding "
            + "a part voids the kit; two shipments of whole kits; units, package_line and COD counted once")
    void aKitOfTwoShipsWhole() throws Exception {
        stock(2);
        OrderSummary order = depositOrder(DepositBalancePolicy.ON_DELIVERY, 2);        // 10M, deposit 3M, COD 7M
        UUID sofaLine = lineOf(order, SOFA);
        UUID task = pickedTask(order);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packageCount").value(2));

        // The station asks for the size it shows; another one is stale data: 422, nothing opened.
        openKit(task, "{\"orderLineId\":\"" + sofaLine + "\",\"partCount\":3}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("KIT_PART_COUNT_MISMATCH"));
        openKit(task, "{}").andExpect(status().isBadRequest());
        JsonNode opened = data(openKit(task, "{\"orderLineId\":\"" + sofaLine + "\",\"partCount\":2}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.partCount").value(2))
                .andExpect(jsonPath("$.data.sku").value(SOFA.code()))
                .andExpect(jsonPath("$.data.packages.length()").value(2))
                .andExpect(jsonPath("$.data.packages[0].kit.partNo").value(1))
                .andExpect(jsonPath("$.data.packages[1].kit.partNo").value(2))
                .andExpect(jsonPath("$.data.packages[1].kit.partCount").value(2))
                .andExpect(jsonPath("$.data.packages[1].status").value("OPEN")));
        String group = opened.path("kitGroupId").asText();
        assertThat(opened.path("packages").findValuesAsText("groupId")).containsOnly(group);
        UUID a = UUID.fromString(opened.path("packages").get(0).path("packageId").asText());
        UUID b = UUID.fromString(opened.path("packages").get(1).path("packageId").asText());

        scanAs(packerHcm, task, b, scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KIT_CONTENTS_INVALID"));
        seal(task, b, MEASURES).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKAGE_EMPTY"));
        scanAs(packerHcm, task, a, scan(UUID.randomUUID(), SOFA.code(), null, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KIT_CONTENTS_INVALID"));
        scanAs(packerHcm, task, a, scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
        seal(task, a, MEASURES).andExpect(status().isOk()).andExpect(jsonPath("$.data.kit.partNo").value(1));
        seal(task, b, "{\"weightKg\":20,\"lengthCm\":95,\"widthCm\":90,\"heightCm\":40}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heightCm").value(40)).andExpect(jsonPath("$.data.contents.length()").value(0));
        packer(get(BASE + "/tasks/" + task + "/packages/" + b + "/label")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kit.groupId").value(group))
                .andExpect(jsonPath("$.data.kit.partNo").value(2)).andExpect(jsonPath("$.data.kit.partCount").value(2))
                .andExpect(jsonPath("$.data.contents.length()").value(0));

        // The second sofa's kit: part 1 sealed, part 2 not - nothing ships, whole or partial.
        List<UUID> second = kit(task, sofaLine);
        scanAs(packerHcm, task, second.get(0), scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
        seal(task, second.get(0), MEASURES).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KIT_INCOMPLETE"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.containsString(
                        "PKG-" + order.orderNumber() + "-4")));
        String partial = "{\"partial\":true,\"note\":\"one sofa now\"}";
        packer(post(BASE + "/tasks/" + task + "/packing-completion").contentType("application/json").content(partial))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("KIT_INCOMPLETE"));

        // Taking its part 2 apart takes the kit apart: the first kit leaves alone, part of the order.
        packer(delete(BASE + "/tasks/" + task + "/packages/" + second.get(1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packed").value(1));
        assertThat(sum("SELECT count(*) FROM fulfillment.package WHERE kit_group_id = (SELECT kit_group_id FROM "
                + "fulfillment.package WHERE id = ?) AND status = 'VOIDED'", second.get(0))).isEqualTo(2);
        String s1 = data(packer(post(BASE + "/tasks/" + task + "/packing-completion").contentType("application/json")
                .content(partial)).andExpect(status().isOk())).path("shipmentId").asText();

        List<UUID> third = kit(task, sofaLine);
        scanAs(packerHcm, task, third.get(0), scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
        for (UUID part : third) {
            seal(task, part, MEASURES).andExpect(status().isOk());
        }
        String s2 = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packStatus").value("PACKED"))).path("shipmentId").asText();

        // Each shipment holds one whole kit: two parcels, one sofa.
        for (String shipment : List.of(s1, s2)) {
            assertThat(sum("SELECT package_count FROM fulfillment.shipment WHERE id = ?::uuid", shipment)).isEqualTo(2);
            assertThat(sum("SELECT count(DISTINCT kit_group_id) FROM fulfillment.package WHERE shipment_id = ?::uuid",
                    shipment)).isEqualTo(1);
            assertThat(sum("SELECT COALESCE(SUM(pl.quantity), 0) FROM fulfillment.package_line pl JOIN "
                    + "fulfillment.package p ON p.id = pl.package_id WHERE p.shipment_id = ?::uuid", shipment)).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT shipment_id::text FROM fulfillment.package WHERE id IN (?, ?)", String.class,
                a, b)).containsOnly(s1);
        // Units once: package_line only on parts 1, 2 sofas for 4 sealed parcels; the order is PACKED, not over-packed.
        assertThat(sum("SELECT count(*) FROM fulfillment.package_line pl JOIN fulfillment.package p ON p.id = "
                + "pl.package_id WHERE pl.order_line_id = ? AND p.status <> 'VOIDED'", sofaLine)).isEqualTo(2);
        assertThat(sum("SELECT count(*) FROM fulfillment.package_line pl JOIN fulfillment.package p ON p.id = "
                + "pl.package_id WHERE pl.order_line_id = ? AND p.kit_part_no > 1", sofaLine)).isZero();
        assertThat(sum("SELECT COALESCE(SUM(pl.quantity), 0) FROM fulfillment.package_line pl JOIN fulfillment.package p "
                + "ON p.id = pl.package_id WHERE pl.order_line_id = ? AND p.status = 'SEALED'", sofaLine)).isEqualTo(2);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        // 09 BR-04: 5M of goods each, 3M paid ahead - S1 collects 2M, S2 5M; parts 2 add nothing.
        assertThat(cod(s1)).isEqualTo(2_000_000L);
        assertThat(cod(s2)).isEqualTo(5_000_000L);
        assertThat(data(packer(get(BASE + "/tasks/" + task + "/packages/" + b + "/label"))).path("codAmount")
                .decimalValue()).isEqualByComparingTo("2000000");
        // The shipment's view carries the kit of every parcel.
        packer(get(BASE + "/shipments/" + s1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packages[*].kit.groupId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(group))));
    }

    @Test
    @DisplayName("LP2-5 a kit of 3 (an item of package_count 3): three parcels, one unit; a one-box item has no kit; the "
            + "database refuses a broken kit - columns, part numbers, goods in part 2, another order, a part left behind")
    void aKitOfThreeAndTheDatabaseRules() throws Exception {
        UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                TABLE.code());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.inventory_items SET package_count = 3 WHERE id = ?",
                item));
        try {
            stock(2);
            OrderSummary order = paidOrder(table(1), sofa(1));
            UUID tableLine = lineOf(order, TABLE);
            UUID task = pickedTask(order);
            packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
            List<UUID> parts = kit(task, tableLine);
            assertThat(parts).hasSize(3);
            scanAs(packerHcm, task, parts.get(0), scan(UUID.randomUUID(), SOFA.code(), null, 1))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("KIT_PART_COUNT_MISMATCH"));
            scanAs(packerHcm, task, parts.get(0), scan(UUID.randomUUID(), TABLE.code(), TABLE_LOT, 1))
                    .andExpect(status().isOk());
            for (UUID part : parts) {
                seal(task, part, MEASURES).andExpect(status().isOk());
            }
            // The sofa goes in an ordinary parcel: kits are the packer's choice ("đóng bộ"), not forced.
            UUID sofaBox = openPackage(task);
            scanAs(packerHcm, task, sofaBox, scan(UUID.randomUUID(), SOFA.code(), null, 1)).andExpect(status().isOk());
            seal(task, sofaBox, MEASURES).andExpect(status().isOk()).andExpect(jsonPath("$.data.kit").doesNotExist());
            String shipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion"))
                    .andExpect(status().isOk())).path("shipmentId").asText();
            assertThat(sum("SELECT package_count FROM fulfillment.shipment WHERE id = ?::uuid", shipment)).isEqualTo(4);
            assertThat(sum("SELECT COALESCE(SUM(quantity), 0) FROM fulfillment.package_line WHERE order_line_id = ?",
                    tableLine)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT kit_part_no || '/' || kit_part_count FROM fulfillment.package "
                    + "WHERE id IN (?, ?, ?) ORDER BY kit_part_no", String.class, parts.get(0), parts.get(1),
                    parts.get(2))).containsExactly("1/3", "2/3", "3/3");

            // The database holds the same rules.
            UUID part2 = parts.get(1);
            UUID part3 = parts.get(2);
            assertThat(refused("UPDATE fulfillment.package SET kit_part_no = NULL WHERE id = ?", part2))
                    .contains("ck_package_kit");
            assertThat(refused("UPDATE fulfillment.package SET kit_part_no = 4 WHERE id = ?", part2))
                    .contains("ck_package_kit");
            assertThat(refused("UPDATE fulfillment.package SET kit_group_id = NULL WHERE id = ?", part2))
                    .contains("ck_package_kit");
            assertThat(refused("UPDATE fulfillment.package SET kit_part_count = 4 WHERE id = ?", part2))
                    .contains("agree on its size");
            assertThat(refused("UPDATE fulfillment.package SET kit_part_no = 2 WHERE id = ?", part3))
                    .contains("uk_package_kit_part");
            assertThat(refused("INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity, version, "
                    + "created_at) VALUES (gen_random_uuid(), ?, ?, 1, 0, NOW())", part2, tableLine))
                    .contains("only part 1 of a kit holds goods");
            assertThat(refused("UPDATE fulfillment.package SET shipment_id = NULL WHERE id = ?", part3))
                    .contains("ships whole");
            OrderSummary other = paidOrder(sofa(1));
            pickedTask(other);
            UUID otherPack = jdbc.queryForObject("SELECT k.id FROM fulfillment.pack k JOIN fulfillment.pick p ON "
                    + "p.id = k.pick_id WHERE p.order_id = ?", UUID.class, other.orderId());
            assertThat(refused("INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, package_type, "
                    + "status, rotatable, rotatable_overridden, fragile, fragile_overridden, kit_group_id, kit_part_no, "
                    + "kit_part_count, version, created_at) SELECT gen_random_uuid(), ?, 99, 'PKG-X-99', 'CARTON', 'OPEN', "
                    + "TRUE, FALSE, FALSE, FALSE, kit_group_id, 3, 3, 0, NOW() FROM fulfillment.package WHERE id = ?",
                    otherPack, part2)).contains("one order");
        } finally {
            tx.executeWithoutResult(s -> jdbc.update(
                    "UPDATE inventory.inventory_items SET package_count = 1 WHERE id = ?", item));
        }
        // A one-box item has no kit.
        OrderSummary plain = paidOrder(table(1));
        UUID task = pickedTask(plain);
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].packageCount").value(1));
        openKit(task, "{\"orderLineId\":\"" + lineOf(plain, TABLE) + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("KIT_PART_COUNT_MISMATCH"));
    }

    /** The statement, committed on its own: what the database said when it refused it. */
    private String refused(String sql, Object... args) {
        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(sql, args)));
        assertThat(thrown).as(sql).isNotNull();
        StringBuilder messages = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
