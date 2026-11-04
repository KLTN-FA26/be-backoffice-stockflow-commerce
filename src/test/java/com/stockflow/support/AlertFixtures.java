package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Rows for the stock-alert tests (SCRUM-147): a SKU of their own with thresholds, stock rows (a lot that has already
 * expired included), staff of a role and warehouse, a second warehouse. Everything is created with unique codes, so a
 * test never disturbs the demo stock or another test.
 *
 * <p>The SKU's policy is left unconfigured ({@code policy_configured = false}), which switches the stock-row
 * policy trigger off: that trigger refuses a lot that is already past its expiry date, and an expired lot is exactly
 * what the expiry sweep tests need to have on the shelf.</p>
 */
public final class AlertFixtures {

    public static final String HCM_BIN = "HCM-A01-1-B";
    public static final String HCM_BIN_2 = "HCM-A02-2-A";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;

    public AlertFixtures(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    public UUID hcm() {
        return jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    /** A new ACTIVE warehouse with one receiving area, {@code PREFIX-RCV01}; returns the prefix, and its id in the map. */
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

    /** A SKU (product, variant and the inventory item the variant creates) with thresholds; null leaves one unset. */
    public String sku(Integer reorderPoint, Integer safetyStock) {
        return sku("ALERT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), reorderPoint, safetyStock);
    }

    /**
     * The same with a SKU code of the caller's choosing, for tests that page through SKUs in order. The SKU is one that
     * may be bought (an ACTIVE variant of an APPROVED product): low-stock alerts are raised only for such a SKU.
     */
    public String sku(String sku, Integer reorderPoint, Integer safetyStock) {
        return sku(sku, reorderPoint, safetyStock, true);
    }

    /** A SKU of a DRAFT product (its variant DRAFT too): nobody may put it on a purchase order yet. */
    public String draftSku(Integer reorderPoint, Integer safetyStock) {
        return sku("ALERT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), reorderPoint,
                safetyStock, false);
    }

    /** Puts the SKU's product back to DRAFT, as if it had never been approved: its SKU is no longer purchasable. */
    public void makeDraft(String sku) {
        tx.executeWithoutResult(s -> jdbc.update("update product.products set status = 'DRAFT', approved_by = null,"
                + " approved_at = null where id = (select product_id from product.variants where sku = ?)", sku));
    }

    private String sku(String sku, Integer reorderPoint, Integer safetyStock, boolean purchasable) {
        UUID approver = purchasable ? tx.execute(s -> ReferenceRows.user(entityManager)) : null;
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug,status,approved_by,approved_at)"
                            + " values (?,?,?,?,?,?,?)", product, sku, "Alert fixture", sku.toLowerCase(Locale.ROOT),
                    purchasable ? "APPROVED" : "DRAFT", approver,
                    purchasable ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature,status)"
                            + " values (?,?,?,'Alert fixture','',?)",
                    Identifiers.newId(), product, sku, purchasable ? "ACTIVE" : "DRAFT");
            jdbc.update("update inventory.inventory_items set reorder_point = ?, safety_stock = ? where sku = ?",
                    reorderPoint, safetyStock, sku);
        });
        return sku;
    }

    public void setThresholds(String sku, Integer reorderPoint, Integer safetyStock) {
        tx.executeWithoutResult(s -> jdbc.update("update inventory.inventory_items set reorder_point = ?, safety_stock = ? where sku = ?",
                reorderPoint, safetyStock, sku));
    }

    /** One stock row; {@code expiry} and {@code lot} may be null. Returns its id. */
    public UUID stock(String sku, String location, int onHand, String status, String lot, LocalDate expiry) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date,"
                + " on_hand, reserved, status, version, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0, NOW())",
                id, sku, location, lot, expiry, onHand, status));
        return id;
    }

    public void setOnHand(UUID stockItem, int onHand) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_item SET on_hand = ? WHERE id = ?", onHand, stockItem));
    }

    /** Holds {@code quantity} of the stock row for an order that exists, as a live reservation. */
    public void hold(UUID stockItem, int quantity) {
        tx.executeWithoutResult(s -> {
            UUID order = Identifiers.newId();
            jdbc.update("INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,"
                    + " currency, placed_at, created_at, paid_amount, paid_in_full_at)"
                    + " VALUES (?, ?, 'c0000000-0000-4000-8000-000000000001', ?, 'CONFIRMED', 1000, 'VND', NOW(), NOW(), 1000, NOW())",
                    order, "SO-AL-" + order.toString().substring(24), Identifiers.newId());
            jdbc.update("INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id,"
                    + " quantity, reserved_at, expires_at, status) VALUES (?, ?, ?, ?, ?, ?, NOW(), NULL, 'HELD')",
                    Identifiers.newId(), stockItem, order, Identifiers.newId(), Identifiers.newId(), quantity);
            jdbc.update("UPDATE inventory.stock_item SET reserved = ? WHERE id = ?", quantity, stockItem);
        });
    }

    public void release(UUID stockItem) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'ORDER_CANCELLED',"
                    + " closed_at = NOW() WHERE stock_item_id = ? AND status = 'HELD'", stockItem);
            jdbc.update("UPDATE inventory.stock_item SET reserved = 0 WHERE id = ?", stockItem);
        });
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

    public long messages(UUID user, String kindPrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ?"
                + " AND dedupe_key LIKE ?", Long.class, user, kindPrefix + ":%");
    }

    public List<String> titles(UUID user) {
        return jdbc.queryForList("SELECT title FROM notification.user_notification WHERE recipient_id = ? ORDER BY created_at",
                String.class, user);
    }
}
