package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The stock the assistant's tests read, as the warehouse keeps it since the rescope (ADR-0010): a SKU whose variant made
 * its inventory item, and whole sealed LPN cartons of it ({@link CartonRows}), received at a given instant, since
 * first in, first out by the real receipt day is the picking order. What the removed {@code AlertFixtures} gave these
 * tests, without reorder thresholds or expiry dates: neither exists any more.
 */
public final class AssistantStock {

    /** Two bins of the demo map (V20261111000001 §4), in different shelves. */
    public static final String HCM_BIN = "HCM-A01-1-B";
    public static final String HCM_BIN_2 = "HCM-A02-2-A";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;

    public AssistantStock(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    /** A new SKU with a random code: product, ACTIVE variant of an APPROVED product, and the item the variant makes. */
    public String sku() {
        return sku("AST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
    }

    /** The same, with the caller's code. */
    public String sku(String sku) {
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug,status,approved_by,approved_at)"
                    + " values (?,?,?,?,'APPROVED',?,now())", product, sku, "Assistant fixture",
                    sku.toLowerCase(Locale.ROOT), approver);
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature,status)"
                    + " values (?,?,?,'Assistant fixture','','ACTIVE')", Identifiers.newId(), product, sku);
        });
        return sku;
    }

    /**
     * {@code cartons} AVAILABLE cartons of {@code unitsPerCarton} units of {@code sku} at {@code location}, in lot
     * {@code lot}, received at {@code receivedAt}.
     *
     * @return the stock row of the first carton
     */
    public UUID cartons(String sku, String location, int cartons, int unitsPerCarton, String lot, Instant receivedAt) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, location, cartons, unitsPerCarton, lot, receivedAt)
                .getFirst().stockItemId());
    }

    /** A carton allocated to an order's pick: the hold on its stock row, and the carton's LPN a pick line names. */
    public record Allocated(UUID reservationId, String lpn) {
    }

    /**
     * One carton of {@code units} units of {@code sku} at {@code location}, in {@code lot}, held whole on its stock row
     * for {@code order}: what a pick line picks ({@code tg_pick_line_carton} wants the line's LPN to be its hold's
     * carton). Call inside a transaction.
     */
    public Allocated allocated(UUID order, String sku, String location, int units, String lot) {
        CartonRows.Carton carton = CartonRows.carton(jdbc, sku, location, units, lot, Instant.now(), "AVAILABLE");
        UUID hold = Identifiers.newId();
        jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id,
                                                         quantity, reserved_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), 'HELD')""", hold, carton.stockItemId(), sku, order,
                Identifiers.newId(), Identifiers.newId(), units);
        jdbc.update("UPDATE inventory.stock_item SET reserved = ? WHERE id = ?", units, carton.stockItemId());
        return new Allocated(hold, carton.lpn());
    }

    /** An ACTIVE account holding {@code role}, assigned to the warehouses given by id. */
    public UUID staff(String role, UUID... warehouses) {
        return new StaffFixtures(jdbc, tx, entityManager).staff(role, warehouses);
    }
}
