package com.stockflow.fulfillment;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-466 through the real filter chain, permission aspect and warehouse scope: a task carries its order
 * number and warehouse, who can be assigned is limited to that warehouse's staff, and a warehouse manager sees
 * only the tasks of their own warehouse. Two warehouses (the demo {@code HCM} and one made here), users with
 * real roles and warehouse assignments, and tokens for them.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as PickDamageIntegrationTest, so the application context is shared: signing a contract
// stores its scan, and no virus scanner runs in tests.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class FulfillmentTaskContextIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;

    private UUID hn;
    private UUID coordinator;
    private UUID managerHcm;
    private UUID managerHn;
    private UUID pickerHcm;
    private UUID otherPickerHcm;
    private UUID pickerHn;
    private UUID salesUser;
    private UUID customerUser;
    private final List<UUID> placed = new ArrayList<>();
    private ConfirmedOrders confirmedOrders;
    /** The demo customer's own sofa (a contract's lines are only ever that, 14 BR-04), with stock at HCM. */
    private Sku sofa;
    /** Its carton unit: two sofas, the size the orders and the stock both come in (whole cartons, 4C-2). */
    private CustomerSkuRows.Issued sofaCarton;
    /** Orders inserted as rows by {@link #taskRow}: removed again with their picks. */
    private final List<UUID> rowOrders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        sofaCarton = CustomerSkuRows.like(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, "SOFA-3S-GREY", 2);
        sofa = new Sku(sofaCarton.sku());
        CustomerSkuRows.stock(jdbc, tx, sofa.code(), 10);
        hn = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Second warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                hn, "F" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
        coordinator = staff("SYSTEM_ADMIN");
        managerHcm = staff("WAREHOUSE_MANAGER", HCM);
        managerHn = staff("WAREHOUSE_MANAGER", hn);
        pickerHcm = staff("WAREHOUSE_STAFF", HCM);
        otherPickerHcm = staff("WAREHOUSE_STAFF", HCM);
        pickerHn = staff("WAREHOUSE_STAFF", hn);
        salesUser = staff("SALES_STAFF");
        customerUser = staff("CUSTOMER");
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> rowOrders.forEach(order -> {
            jdbc.update("DELETE FROM fulfillment.pack WHERE pick_id IN (SELECT id FROM fulfillment.pick WHERE order_id = ?)",
                    order);
            jdbc.update("DELETE FROM fulfillment.pick WHERE order_id = ?", order);
            jdbc.update("DELETE FROM ordering.customer_order WHERE id = ?", order);
        }));
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus current = orders.findById(id).orElseThrow().status();
            if (current != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
    }

    /** An active account holding one role, assigned to a warehouse when given one. */
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

    /**
     * A confirmed order of the demo customer - one carton of two sofas, its contract paid on signing - shipping from
     * HCM, its sofas held there, released there (READY_TO_FULFILL).
     */
    private OrderSummary confirmedOrder() {
        // G3-2 (kltn-docs 17 BR-02): only a released order enters fulfillment.
        return confirmedOrders.release(paidOrder().orderId(), DemoData.WAREHOUSE_HCM, coordinator);
    }

    /** Signed and its installment on signing paid: CONFIRMED, no shipping warehouse chosen yet, nothing held. */
    private OrderSummary paidOrder() {
        UUID order = confirmedOrders.confirmed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(sofaCarton, 1, 12_000_000)).orderId();
        placed.add(order);
        return orders.findById(order).orElseThrow();
    }

    /** A task of {@code warehouse} written as a row: how it got there is not what these tests are about. */
    private UUID taskRow(UUID warehouse, UUID assignee, String pickStatus) {
        UUID order = Identifiers.newId();
        UUID pick = Identifiers.newId();
        rowOrders.add(order);
        String number = "SO-FT-" + order.toString().substring(24).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at, warehouse_id)
                    VALUES (?, ?, ?, ?, 'PICKING', 1000, 'VND', NOW(), 0, NOW(), ?)""",
                    order, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), warehouse);
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                                  assigned_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())""", pick, order, number, warehouse, pickStatus, assignee);
            jdbc.update("INSERT INTO fulfillment.pack (id, pick_id, status, created_at) VALUES (?, ?, 'PENDING', NOW())",
                    Identifiers.newId(), pick);
        });
        return pick;
    }

    private ResultActions call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String create(UUID orderId, UUID assignee) {
        return "{\"orderId\":\"" + orderId + "\",\"assignedUserId\":\"" + assignee + "\"}";
    }

    // ---- creating a task ---------------------------------------------------------------------

    @Test
    @DisplayName("a task from a confirmed order carries the order number, the warehouse of its holds and the assignee's name")
    void createStoresTheContext() throws Exception {
        OrderSummary order = confirmedOrder();
        String oc = token(coordinator, "SYSTEM_ADMIN");

        call(post("/api/v1/fulfillment/tasks").header("Authorization", oc).contentType("application/json")
                .content(create(order.orderId(), pickerHcm)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.data.warehouseId").value(HCM.toString()))
                .andExpect(jsonPath("$.data.assignedUserId").value(pickerHcm.toString()))
                .andExpect(jsonPath("$.data.assignedUserName").value("test-" + pickerHcm))
                .andExpect(jsonPath("$.data.pickStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.lines").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT order_number FROM fulfillment.pick WHERE order_id = ?", String.class,
                order.orderId())).isEqualTo(order.orderNumber());
        assertThat(jdbc.queryForObject("SELECT warehouse_id FROM fulfillment.pick WHERE order_id = ?", UUID.class,
                order.orderId())).isEqualTo(HCM);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
        // The same request again is answered with the same task.
        call(post("/api/v1/fulfillment/tasks").header("Authorization", oc).contentType("application/json")
                .content(create(order.orderId(), pickerHcm)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderNumber").value(order.orderNumber()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Integer.class,
                order.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("an assignee of another warehouse is refused on assignedUserId and nothing is created")
    void assigneeFromAnotherWarehouse() throws Exception {
        OrderSummary order = confirmedOrder();
        String oc = token(coordinator, "SYSTEM_ADMIN");

        call(post("/api/v1/fulfillment/tasks").header("Authorization", oc).contentType("application/json")
                .content(create(order.orderId(), pickerHn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assignedUserId"));
        call(post("/api/v1/fulfillment/tasks").header("Authorization", oc).contentType("application/json")
                .content(create(order.orderId(), salesUser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assignedUserId"));
        call(post("/api/v1/fulfillment/tasks").header("Authorization", oc).contentType("application/json")
                .content(create(order.orderId(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assignedUserId"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Integer.class,
                order.orderId())).isZero();
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("a release from a shipping warehouse that holds none of the order is refused with 409, and nothing moves")
    void holdsOutsideTheOrderWarehouse() throws Exception {
        OrderSummary order = paidOrder();
        // 4B-7 (17 §4.2 step 5): a contract's order is released from its shipping warehouse once every carton is held
        // there. The second warehouse has no sofa, so choosing it holds nothing and the release is refused - before the
        // order is READY_TO_FULFILL, rather than at task creation.
        confirmedOrders.ship(order.orderId(), hn);
        BusinessException refused = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(
                () -> releases.release(order.orderId(), hn, coordinator));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);
        assertThat(refused.getMessage()).contains(sofa.code());
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT status FROM inventory.stock_reservation WHERE order_id = ?",
                String.class, order.orderId())).as("nothing of the sofa held at the second warehouse").isEmpty();

        call(post("/api/v1/fulfillment/tasks").header("Authorization", token(coordinator, "SYSTEM_ADMIN"))
                .contentType("application/json").content(create(order.orderId(), pickerHn)))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Integer.class,
                order.orderId())).isZero();
    }

    @Test
    @DisplayName("a manager of another warehouse cannot open a task in this one: 403 OUT_OF_DATA_SCOPE")
    void managerOfAnotherWarehouseCannotCreate() throws Exception {
        OrderSummary order = confirmedOrder();

        call(post("/api/v1/fulfillment/tasks").header("Authorization", token(managerHn, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content(create(order.orderId(), pickerHn)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        // The manager of the right warehouse can.
        call(post("/api/v1/fulfillment/tasks").header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content(create(order.orderId(), pickerHcm)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(HCM.toString()));
    }

    @Test
    @DisplayName("a picker cannot create a task, and a customer or a sales user cannot reach the fulfillment endpoints")
    void rolesWithoutAccess() throws Exception {
        OrderSummary order = confirmedOrder();

        call(post("/api/v1/fulfillment/tasks").header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(create(order.orderId(), pickerHcm)))
                .andExpect(status().isForbidden());
        for (String[] who : new String[][] {{"CUSTOMER"}, {"SALES_STAFF"}}) {
            UUID user = who[0].equals("CUSTOMER") ? customerUser : salesUser;
            String header = token(user, who[0]);
            call(get("/api/v1/fulfillment/tasks").header("Authorization", header)).andExpect(status().isForbidden());
            call(get("/api/v1/fulfillment/assignees").param("warehouseId", HCM.toString())
                    .header("Authorization", header)).andExpect(status().isForbidden());
            call(post("/api/v1/fulfillment/tasks").header("Authorization", header).contentType("application/json")
                    .content(create(order.orderId(), pickerHcm))).andExpect(status().isForbidden());
        }
        call(get("/api/v1/fulfillment/tasks")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Integer.class,
                order.orderId())).isZero();
    }

    // ---- reading tasks -----------------------------------------------------------------------

    @Test
    @DisplayName("the detail carries the lines to pick; the assignee sees it, another picker gets 404, another warehouse's manager 403")
    void detailCarriesLines() throws Exception {
        OrderSummary order = confirmedOrder();
        call(post("/api/v1/fulfillment/tasks").header("Authorization", token(coordinator, "SYSTEM_ADMIN"))
                .contentType("application/json").content(create(order.orderId(), pickerHcm)))
                .andExpect(status().isCreated());
        UUID task = jdbc.queryForObject("SELECT id FROM fulfillment.pick WHERE order_id = ?", UUID.class,
                order.orderId());
        String url = "/api/v1/fulfillment/tasks/" + task;

        call(get(url).header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.data.warehouseId").value(HCM.toString()))
                .andExpect(jsonPath("$.data.assignedUserName").value("test-" + pickerHcm))
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].lineId").value(order.lines().getFirst().lineId().toString()))
                .andExpect(jsonPath("$.data.lines[0].sku").value(sofa.code()))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.data.lines[0].printLine").value(false))
                .andExpect(jsonPath("$.data.lines[0].designSnapshotId").doesNotExist());
        call(get(url).header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        call(get(url).header("Authorization", token(managerHn, "WAREHOUSE_MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(get(url).header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines.length()").value(1));
        // A write answer names the same task but does not repeat the lines.
        call(put(url + "/assignment").header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content("{\"assignedUserId\":\"" + otherPickerHcm + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedUserId").value(otherPickerHcm.toString()))
                .andExpect(jsonPath("$.data.lines").doesNotExist());
        call(put(url + "/assignment").header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content("{\"assignedUserId\":\"" + pickerHn + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("assignedUserId"));
        call(put(url + "/assignment").header("Authorization", token(managerHn, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content("{\"assignedUserId\":\"" + pickerHn + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    @Test
    @DisplayName("the list is limited to the caller's warehouses and filters by status, warehouse and assignee")
    void listScopeAndFilters() throws Exception {
        UUID inHcm = taskRow(HCM, pickerHcm, "PENDING");
        UUID inHcmPicking = taskRow(HCM, otherPickerHcm, "PICKING");
        UUID inHn = taskRow(hn, pickerHn, "PENDING");
        String manager = token(managerHcm, "WAREHOUSE_MANAGER");

        // A manager of HCM: tasks of HCM only, however many other tests left behind.
        String body = call(get("/api/v1/fulfillment/tasks").param("size", "200").header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].warehouseId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(HCM.toString()))))
                .andExpect(jsonPath("$.data.items[*].taskId", org.hamcrest.Matchers.hasItems(inHcm.toString(),
                        inHcmPicking.toString())))
                .andExpect(jsonPath("$.data.items[*].taskId",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(inHn.toString()))))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"orderNumber\":\"SO-FT-");

        call(get("/api/v1/fulfillment/tasks").param("warehouseId", hn.toString()).header("Authorization", manager))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(get("/api/v1/fulfillment/tasks").param("warehouseId", HCM.toString()).param("status", "picking")
                .param("size", "200").header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].pickStatus",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PICKING"))))
                .andExpect(jsonPath("$.data.items[*].taskId", org.hamcrest.Matchers.hasItem(inHcmPicking.toString())))
                .andExpect(jsonPath("$.data.items[*].taskId",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(inHcm.toString()))));
        call(get("/api/v1/fulfillment/tasks").param("assignedUserId", pickerHcm.toString()).header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(inHcm.toString()))
                .andExpect(jsonPath("$.data.items[0].assignedUserName").value("test-" + pickerHcm));
        call(get("/api/v1/fulfillment/tasks").param("status", "DONE").header("Authorization", manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));

        // A coordinator is not limited to a warehouse and can look at one.
        call(get("/api/v1/fulfillment/tasks").param("warehouseId", hn.toString())
                .header("Authorization", token(coordinator, "SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(inHn.toString()));
        // A picker sees their own tasks, whatever they ask for.
        call(get("/api/v1/fulfillment/tasks").param("size", "200").header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(inHcm.toString()));
        call(get("/api/v1/fulfillment/tasks").param("warehouseId", hn.toString())
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    // ---- who can be assigned -----------------------------------------------------------------

    @Test
    @DisplayName("assignees: the active warehouse staff and managers of that warehouse, by name only; scoped and permissioned")
    void assignees() throws Exception {
        String oc = token(coordinator, "SYSTEM_ADMIN");

        call(get("/api/v1/fulfillment/assignees").param("warehouseId", HCM.toString()).header("Authorization", oc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].userId", org.hamcrest.Matchers.hasItems(pickerHcm.toString(),
                        otherPickerHcm.toString(), managerHcm.toString())))
                .andExpect(jsonPath("$.data[*].userId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItems(
                        pickerHn.toString(), managerHn.toString(), salesUser.toString(), coordinator.toString()))))
                .andExpect(jsonPath("$.data[?(@.userId=='" + pickerHcm + "')].displayName",
                        org.hamcrest.Matchers.contains("test-" + pickerHcm)))
                .andExpect(jsonPath("$.data[?(@.userId=='" + managerHcm + "')].roles[0]",
                        org.hamcrest.Matchers.contains("WAREHOUSE_MANAGER")))
                .andExpect(jsonPath("$.data[*].email").doesNotExist())
                .andExpect(jsonPath("$.data[*].phone").doesNotExist())
                .andExpect(jsonPath("$.data[*].status").doesNotExist());
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", hn.toString()).header("Authorization", oc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        // A deactivated account is not offered.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?", pickerHn));
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", hn.toString()).header("Authorization", oc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(managerHn.toString()));

        String manager = token(managerHcm, "WAREHOUSE_MANAGER");
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", HCM.toString()).header("Authorization", manager))
                .andExpect(status().isOk());
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", hn.toString()).header("Authorization", manager))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", HCM.toString())
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isForbidden());
        call(get("/api/v1/fulfillment/assignees").header("Authorization", oc))
                .andExpect(status().isBadRequest());
        call(get("/api/v1/fulfillment/assignees").param("warehouseId", UUID.randomUUID().toString())
                .header("Authorization", oc))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WAREHOUSE_NOT_FOUND"));
    }
}
