package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

/**
 * Staff accounts of a role and warehouse, and a second warehouse, for the tests that ask who is told or who may act
 * where. Everything is created with unique codes, so a test never disturbs the demo rows or another test.
 *
 * <p>What is left of the stock-alert fixtures once stock alerts were removed (team plan 5-1, kltn-docs 20 BR-13): the
 * recipient and warehouse-scope tests still need people and places, not thresholds.</p>
 */
public final class StaffFixtures {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;

    public StaffFixtures(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    public UUID hcm() {
        return jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    /** A new ACTIVE warehouse with one receiving area, {@code PREFIX-RCV01}. */
    public record Warehouse(UUID id, String prefix, String area) {
    }

    public Warehouse otherWarehouse() {
        UUID id = Identifiers.newId();
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        String area = prefix + "-RCV01";
        UUID location = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,"
                    + " version, created_at) VALUES (?, 'Test warehouse', 'ACTIVE', ?, 'Test address', 'M', 50, 50, 0, NOW())",
                    id, prefix);
            jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,"
                    + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                    location, id, area);
            jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                    + " rotation, is_obstacle, status) VALUES (?, ?, ?, 'RCV01', 'RECEIVING', 'x', 1, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                    Identifiers.newId(), id, location);
        });
        return new Warehouse(id, prefix, area);
    }

    /** An ACTIVE account holding {@code role} and assigned to the given warehouses. */
    public UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)"
                    + " SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", Identifiers.newId(), user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)"
                        + " VALUES (?, ?, ?, 0, NOW())", Identifiers.newId(), user, warehouse);
            }
        });
        return user;
    }

    public void addRole(UUID user, String role) {
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)"
                + " SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", Identifiers.newId(), user, role));
    }

    public void setStatus(UUID user, String status) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = ? WHERE id = ?", status, user));
    }

    public long messages(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ?", Long.class, user);
    }

    public java.util.List<String> titles(UUID user) {
        return jdbc.queryForList("SELECT title FROM notification.user_notification WHERE recipient_id = ? ORDER BY created_at",
                String.class, user);
    }
}
