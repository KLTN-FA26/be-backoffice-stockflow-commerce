package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the warehouse manager sees of the sales orders (V20261115000200, team plan 5-7 review M1; kltn-docs 17 §2, BR-03,
 * BR-27; 00 §2.5.3): the list, the order and its history, but only the orders that ship from, or were released to, a
 * warehouse they are assigned to - nothing when they are assigned none. Sales (scope ALL) sees everything, and a user
 * holding the manager role and an ALL-scope role is not limited.
 */
@SecuredIntegrationTest
class OrderWarehouseVisibilityHttpTest {

    private static final String ORDERS = "/api/v1/orders";

    private static final java.util.concurrent.atomic.AtomicLong ORDER_NUMBERS = new java.util.concurrent.atomic.AtomicLong(770_000);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired com.stockflow.order.internal.domain.OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID here;
    private UUID there;
    private UUID managerHere;
    private UUID managerNowhere;
    private UUID sales;
    private UUID shipsHere;
    private UUID shipsThere;
    private UUID shipsNowhere;
    private UUID releasedHere;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        here = warehouse();
        there = warehouse();
        managerHere = staff("WAREHOUSE_MANAGER", here);
        managerNowhere = staff("WAREHOUSE_MANAGER");
        sales = staff("SALES_STAFF");
        shipsHere = place();
        shipsThere = place();
        shipsNowhere = place();
        releasedHere = place();
        tx.executeWithoutResult(s -> {
            // The columns the rules of 4B-7 and release write, set directly: the test is about who reads, not how it got there.
            // A signed contract's order is past DRAFT (ck_order_contract_signed): CONFIRMED, and released READY_TO_FULFILL.
            jdbc.update("SET LOCAL session_replication_role = replica");
            for (Object[] row : new Object[][] {{shipsHere, here}, {shipsThere, there}, {releasedHere, here}}) {
                jdbc.update("UPDATE ordering.customer_order SET status = 'CONFIRMED', contract_signed_at = NOW(), "
                        + "shipping_warehouse_id = ?, shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = ? "
                        + "WHERE id = ?", row[1], sales, row[0]);
            }
            jdbc.update("UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', warehouse_id = ?, released_at = NOW(), "
                    + "released_by = ? WHERE id = ?", here, sales, releasedHere);
        });
    }

    /** An order of the demo customer, drawn up as Sales draws one up: customers no longer place their own (5-2). */
    private UUID place() {
        return tx.execute(s -> orderRepository.save(com.stockflow.order.internal.domain.Order.draft(
                com.stockflow.order.internal.domain.OrderNumber.of(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")),
                        ORDER_NUMBERS.incrementAndGet()), DemoData.CUSTOMER_ID, Identifiers.newId(),
                List.of(com.stockflow.order.internal.domain.Order.line(Identifiers.newId(), new Sku("SOFA-3S-GREY"), 1,
                        Money.vnd(12_000_000), null)), java.time.Instant.now())).id().value());
    }

    private UUID warehouse() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Visibility warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                id, "V" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
        return id;
    }

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) SELECT "
                    + "gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 0, NOW())", user, warehouse);
            }
        });
        return user;
    }

    @Test
    @DisplayName("the manager lists only the orders of their warehouses: shipping from it, or released to it")
    void listIsLimited() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, managerHere, get(ORDERS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + shipsHere + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + releasedHere + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + shipsThere + "')]").isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + shipsNowhere + "')]").isEmpty());
    }

    @Test
    @DisplayName("the order and its history: 200 for an order of their warehouse, not found for any other")
    void oneOrderAndItsHistory() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, managerHere, get(ORDERS + "/" + shipsHere)).andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, managerHere, get(ORDERS + "/" + shipsHere + "/history"))
                .andExpect(status().isOk());
        for (UUID other : List.of(shipsThere, shipsNowhere)) {
            mvc.asRole(Role.WAREHOUSE_MANAGER, managerHere, get(ORDERS + "/" + other)).andExpect(status().isNotFound());
            mvc.asRole(Role.WAREHOUSE_MANAGER, managerHere, get(ORDERS + "/" + other + "/history"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("a manager assigned no warehouse sees no order at all")
    void unassignedManagerSeesNothing() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, managerNowhere, get(ORDERS).param("size", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.asRole(Role.WAREHOUSE_MANAGER, managerNowhere, get(ORDERS + "/" + shipsHere))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Sales (scope ALL) still sees every order")
    void salesSeesAll() throws Exception {
        mvc.asRole(Role.SALES_STAFF, sales, get(ORDERS).param("size", "200")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + shipsThere + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '" + shipsNowhere + "')]").isNotEmpty());
    }
}
