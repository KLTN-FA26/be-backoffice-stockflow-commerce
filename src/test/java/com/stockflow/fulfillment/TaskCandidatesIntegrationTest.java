package com.stockflow.fulfillment;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /fulfillment/task-candidates through the real filter chain, permission aspect and warehouse scope: the
 * create-task dialog's order list, readable by whoever may open a task (fulfillment-task-assignment:CREATE) - a
 * warehouse manager, who holds no sales-orders:READ - and limited to the caller's warehouses. Two warehouses made
 * here, so every list is exactly the orders of this test.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as FulfillmentTaskContextIntegrationTest, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class TaskCandidatesIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired com.stockflow.common.storage.FileStorage storage;

    private UUID north;
    private UUID south;
    private UUID coordinator;
    private UUID managerNorth;
    private UUID pickerNorth;
    private final List<UUID> rowOrders = new ArrayList<>();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        north = warehouse();
        south = warehouse();
        coordinator = staff("ORDER_COORDINATOR");
        managerNorth = staff("WAREHOUSE_MANAGER", north);
        pickerNorth = staff("WAREHOUSE_STAFF", north);
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> rowOrders.forEach(order -> {
            jdbc.update("DELETE FROM fulfillment.pack WHERE pick_id IN (SELECT id FROM fulfillment.pick WHERE order_id = ?)",
                    order);
            jdbc.update("DELETE FROM fulfillment.pick WHERE order_id = ?", order);
            jdbc.update("DELETE FROM ordering.order_status_history WHERE order_id = ?", order);
            jdbc.update("DELETE FROM ordering.order_line WHERE order_id = ?", order);
            jdbc.update("DELETE FROM ordering.customer_order WHERE id = ?", order);
        }));
    }

    private UUID warehouse() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Candidate warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                id, "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
        return id;
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
     * A credit order of the demo customer in {@code status}, released from {@code warehouse} {@code hoursAgo} ago -
     * or, CONFIRMED, not released yet.
     */
    private UUID order(String status, UUID warehouse, int hoursAgo, int... quantities) {
        UUID order = Identifiers.newId();
        rowOrders.add(order);
        // A well-formed number: opening a task loads the order through its aggregate.
        String number = "SO-20991229-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        Instant released = now.minus(hoursAgo, ChronoUnit.HOURS);
        boolean isReleased = !"CONFIRMED".equals(status);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at, payment_term,
                                                         credit_term_days, warehouse_id, released_at, released_by)
                    VALUES (?, ?, ?, ?, ?, 1000, 'VND', ?, 0, NOW(), 'CREDIT', 30, ?, ?, ?)""",
                    order, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), status,
                    java.sql.Timestamp.from(released.minus(1, ChronoUnit.DAYS)), isReleased ? warehouse : null,
                    isReleased ? java.sql.Timestamp.from(released) : null, isReleased ? coordinator : null);
            for (int quantity : quantities) {
                jdbc.update("""
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                        VALUES (?, ?, 'SOFA-3S-GREY', ?, 10, 'VND')""", Identifiers.newId(), order, quantity);
            }
        });
        return order;
    }

    /** A print line of a confirmed design on {@code order}. */
    private void printLine(UUID order, int quantity) throws Exception {
        var design = com.stockflow.support.ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager,
                DemoData.CUSTOMER_ID);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                 design_checksum)
                VALUES (?, ?, 'SOFA-3S-GREY', ?, 10, 'VND', ?, ?)""", Identifiers.newId(), order, quantity,
                design.snapshotId(), design.checksum()));
    }

    @Test
    @DisplayName("review of #150: a mixed order still printed is listed (its stock is picked in parallel); a print-only "
            + "one is not")
    void earlyShipMixedOrderIsListed() throws Exception {
        UUID mixed = order("IN_PRODUCTION", north, 3, 2);
        printLine(mixed, 5);
        UUID printOnly = order("IN_PRODUCTION", north, 2);
        printLine(printOnly, 4);
        UUID ready = order("READY_TO_FULFILL", north, 1, 1);

        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + north)
                        .header("Authorization", token(managerNorth, "WAREHOUSE_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(mixed.toString(), ready.toString())))
                .andExpect(jsonPath("$.data.items[0].lines", hasSize(2)));
        org.assertj.core.api.Assertions.assertThat(printOnly).isNotNull();
    }

    @Test
    @DisplayName("review of #150: once its task is open the order leaves the list, and stays out after printing "
            + "completes and it is READY_TO_FULFILL again")
    void anOrderWithAnOpenTaskIsNotListed() throws Exception {
        UUID mixed = order("IN_PRODUCTION", north, 3, 2);
        printLine(mixed, 5);
        UUID other = order("READY_TO_FULFILL", north, 1, 1);
        String oc = token(coordinator, "ORDER_COORDINATOR");
        String list = "/api/v1/fulfillment/task-candidates?warehouseId=" + north;

        mvc.perform(get(list).header("Authorization", oc))
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(mixed.toString(), other.toString())));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/fulfillment/tasks")
                        .header("Authorization", oc).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + mixed + "\",\"assignedUserId\":\"" + pickerNorth + "\"}"))
                .andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM ordering.customer_order WHERE id = ?", String.class, mixed))
                .as("picked in parallel").isEqualTo("IN_PRODUCTION");
        mvc.perform(get(list).header("Authorization", oc))
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(other.toString())));

        // Printing completes: the order is READY_TO_FULFILL again (the edge production completion takes), its task
        // still open - it is not offered a second time.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL' WHERE id = ?", mixed));
        mvc.perform(get(list).header("Authorization", oc))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(other.toString())));
    }

    @Test
    @DisplayName("a warehouse manager reads the READY_TO_FULFILL orders of their warehouse, oldest release first")
    void managerSeesOwnWarehouse() throws Exception {
        UUID older = order("READY_TO_FULFILL", north, 5, 3, 4);
        UUID newer = order("READY_TO_FULFILL", north, 1, 2);
        order("PICKING", north, 8, 1);          // a task is open already
        order("CONFIRMED", north, 9, 1);        // not released yet
        order("READY_TO_FULFILL", south, 7, 1); // another warehouse
        String customerName = jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id = ?", String.class,
                DemoData.CUSTOMER_ID);
        String manager = token(managerNorth, "WAREHOUSE_MANAGER");

        for (String query : List.of("", "?warehouseId=" + north)) {
            mvc.perform(get("/api/v1/fulfillment/task-candidates" + query).header("Authorization", manager))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.items[*].orderId").value(contains(older.toString(), newer.toString())))
                    .andExpect(jsonPath("$.data.items[0].customerId").value(DemoData.CUSTOMER_ID.toString()))
                    .andExpect(jsonPath("$.data.items[0].customerName").value(customerName))
                    .andExpect(jsonPath("$.data.items[0].warehouseId").value(north.toString()))
                    .andExpect(jsonPath("$.data.items[0].lineCount").value(2))
                    .andExpect(jsonPath("$.data.items[0].totalQuantity").value(7))
                    .andExpect(jsonPath("$.data.items[0].lines", hasSize(2)))
                    .andExpect(jsonPath("$.data.items[0].lines[0].sku").value("SOFA-3S-GREY"))
                    .andExpect(jsonPath("$.data.items[0].lines[0].printed").value(false));
        }
        // The reason this list exists: the sales order list is not a warehouse manager's.
        mvc.perform(get("/api/v1/orders").header("Authorization", manager)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another warehouse is out of a manager's scope; an unknown one is 404; staff cannot open tasks")
    void scopeAndPermission() throws Exception {
        order("READY_TO_FULFILL", south, 2, 1);

        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + south)
                        .header("Authorization", token(managerNorth, "WAREHOUSE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + UUID.randomUUID())
                        .header("Authorization", token(coordinator, "ORDER_COORDINATOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WAREHOUSE_NOT_FOUND"));
        mvc.perform(get("/api/v1/fulfillment/task-candidates")
                        .header("Authorization", token(pickerNorth, "WAREHOUSE_STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fulfillment/task-candidates")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a coordinator, not limited to warehouses, narrows by warehouse and pages")
    void coordinatorPages() throws Exception {
        UUID first = order("READY_TO_FULFILL", south, 6, 1);
        UUID second = order("READY_TO_FULFILL", south, 4, 1);
        UUID third = order("READY_TO_FULFILL", south, 2, 1);
        String oc = token(coordinator, "ORDER_COORDINATOR");

        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + south + "&page=0&size=2")
                        .header("Authorization", oc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(first.toString(), second.toString())));
        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + south + "&page=1&size=2")
                        .header("Authorization", oc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderId").value(contains(third.toString())));
        mvc.perform(get("/api/v1/fulfillment/task-candidates?warehouseId=" + south + "&size=500")
                        .header("Authorization", oc))
                .andExpect(status().isBadRequest());
    }
}
