package com.stockflow.notification;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the inbox tells about a stock alert (SCRUM-147, plan A6): by staff role, limited to the warehouse concerned,
 * never a customer, at most one message per kind, warehouse, recipient and day. The event is published as the
 * inventory module does, and the listener runs for real.
 */
@SecuredIntegrationTest
class InventoryAlertNotificationIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;

    private AlertFixtures fixtures;
    private UUID hcm;
    private AlertFixtures.Warehouse other;
    private UUID planner;
    private UUID lockedPlanner;
    private UUID admin;
    private UUID managerHcm;
    private UUID managerOther;
    private UUID staffHcm;
    private UUID buyer;
    private UUID customer;
    private UUID plannerWhoIsACustomer;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        hcm = fixtures.hcm();
        other = fixtures.otherWarehouse();
        planner = fixtures.staff("INVENTORY_PLANNER");
        lockedPlanner = fixtures.staff("INVENTORY_PLANNER");
        fixtures.setStatus(lockedPlanner, "LOCKED");
        admin = fixtures.staff("SYSTEM_ADMIN");
        managerHcm = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        managerOther = fixtures.staff("WAREHOUSE_MANAGER", other.id());
        staffHcm = fixtures.staff("WAREHOUSE_STAFF", hcm);
        buyer = fixtures.staff("PROCUREMENT_STAFF");
        customer = fixtures.staff("CUSTOMER");
        plannerWhoIsACustomer = fixtures.staff("INVENTORY_PLANNER");
        fixtures.addRole(plannerWhoIsACustomer, "CUSTOMER");
    }

    private void publish(String kind, UUID warehouse, InventoryAlertsRaised.Item... items) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new InventoryAlertsRaised(UUID.randomUUID(), kind, warehouse,
                List.of(items), Instant.now())));
    }

    private static InventoryAlertsRaised.Item stockItem(String sku) {
        return new InventoryAlertsRaised.Item(UUID.randomUUID(), sku, null, null, 2, 10, false);
    }

    private static InventoryAlertsRaised.Item lotItem(String sku, int days) {
        return new InventoryAlertsRaised.Item(UUID.randomUUID(), sku, "LOT-1", LocalDate.now().plusDays(days), 4, null, false);
    }

    private void awaitMessages(UUID user, long expected) {
        Await.until("messages of " + user + " = " + expected, Duration.ofSeconds(20), () -> fixtures.messages(user) >= expected);
    }

    private List<UUID> nobodyElse(UUID... excluded) {
        List<UUID> all = new java.util.ArrayList<>(List.of(lockedPlanner, admin, managerHcm, managerOther, staffHcm, buyer, customer,
                plannerWhoIsACustomer, planner));
        all.removeAll(List.of(excluded));
        return all;
    }

    private void assertNoMessages(List<UUID> users) {
        assertThat(users).allSatisfy(user -> assertThat(fixtures.messages(user)).as("messages of %s", user).isZero());
    }

    @Test
    void aLowStockAlertGoesToThePlannerAndTheAdministratorOnly() {
        publish("REORDER", null, stockItem("SKU-A"), stockItem("SKU-B"));
        awaitMessages(planner, 1);
        awaitMessages(admin, 1);

        assertThat(fixtures.messages(planner)).isEqualTo(1);
        assertThat(fixtures.messages(admin)).isEqualTo(1);
        // Not the warehouse people, not procurement (the planner tells them), no customer, no locked account.
        assertNoMessages(nobodyElse(planner, admin));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notification.user_notification WHERE recipient_id = ?", planner);
        assertThat(row).containsEntry("category", "INVENTORY_ALERT").containsEntry("severity", "WARN")
                .containsEntry("source_type", "STOCK_ALERT").containsEntry("title", "2 SKU chạm ngưỡng đặt lại hàng")
                .containsEntry("body", "SKU: SKU-A, SKU-B").containsEntry("link", "/admin/inventory/alerts?kind=REORDER");
        assertThat((String) row.get("dedupe_key")).startsWith("REORDER:ALL:");
        assertThat(row.get("read_at")).isNull();
    }

    @Test
    void anExpiryAlertGoesToTheManagerOfThatWarehouseAndTheRolesThatSeeEverythingNotToAnotherWarehouse() {
        publish("EXPIRED", hcm, lotItem("SKU-A", -1));
        awaitMessages(managerHcm, 1);
        awaitMessages(planner, 1);
        awaitMessages(admin, 1);

        assertNoMessages(nobodyElse(managerHcm, planner, admin));
        assertThat(jdbc.queryForObject("SELECT severity FROM notification.user_notification WHERE recipient_id = ?", String.class,
                managerHcm)).isEqualTo("CRITICAL");
        assertThat(jdbc.queryForObject("SELECT link FROM notification.user_notification WHERE recipient_id = ?", String.class,
                managerHcm)).isEqualTo("/admin/inventory/alerts?kind=EXPIRED&warehouseId=" + hcm);
        assertThat(fixtures.titles(managerHcm).get(0)).isEqualTo("1 lô đã hết hạn tại kho HCM");

        publish("EXPIRING", other.id(), lotItem("SKU-B", 5));
        awaitMessages(managerOther, 1);
        assertThat(fixtures.titles(managerOther).get(0)).isEqualTo("1 lô sắp hết hạn tại kho " + other.prefix());
        assertThat(fixtures.messages(managerHcm)).isEqualTo(1);
        assertThat(fixtures.messages(staffHcm)).isZero();
    }

    @Test
    void aCustomerIsNeverTold() {
        publish("REORDER", null, stockItem("SKU-A"));
        publish("EXPIRED", hcm, lotItem("SKU-A", -1));
        awaitMessages(planner, 2);
        assertThat(fixtures.messages(customer)).isZero();
        assertThat(fixtures.messages(plannerWhoIsACustomer)).isZero();
        assertThat(fixtures.messages(lockedPlanner)).isZero();
    }

    @Test
    void aSecondBatchOfTheSameKindAndWarehouseOnTheSameDayIsNotMessagedAgain() {
        publish("REORDER", null, stockItem("SKU-A"));
        awaitMessages(planner, 1);
        publish("REORDER", null, stockItem("SKU-B"));
        publish("SAFETY", null, stockItem("SKU-C"));   // a different kind is a different message
        awaitMessages(planner, 2);
        // Give the second REORDER the chance to (wrongly) arrive: it is processed before the SAFETY one.
        assertThat(fixtures.messages(planner)).isEqualTo(2);
        assertThat(fixtures.messages(planner, "REORDER")).isEqualTo(1);
        assertThat(fixtures.messages(planner, "SAFETY")).isEqualTo(1);
    }

    @Test
    void theSameKindInAnotherWarehouseIsAnotherMessage() {
        publish("EXPIRING", hcm, lotItem("SKU-A", 3));
        awaitMessages(planner, 1);
        publish("EXPIRING", other.id(), lotItem("SKU-A", 3));
        awaitMessages(planner, 2);
        assertThat(fixtures.messages(planner, "EXPIRING")).isEqualTo(2);
    }
}
