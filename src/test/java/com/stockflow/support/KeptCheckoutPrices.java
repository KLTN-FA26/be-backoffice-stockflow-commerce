package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A customer's own price on a stock variant, as one set before 4B-2 was kept: {@code catalog.customer_price} counted in
 * units, one tier from one unit on. Since 5-3 it is the only price the checkout endpoint ({@code POST /api/v1/orders})
 * reads - there is no base or group price (kltn-docs 13 BR-04) - until that endpoint goes (team plan 5-2).
 *
 * <p>{@code tg_customer_price_own_sku} refuses such a row today (4B-2: a new price is for the customer's own SKU), so it
 * is written as an upgrade test writes old data: with triggers off for that one transaction. The row is exactly what
 * the database already holds for prices set before the trigger, nothing a running system could not have.</p>
 */
public final class KeptCheckoutPrices {

    private KeptCheckoutPrices() {
    }

    /** Gives {@code customerId} {@code unitPrice} whole VND a piece for {@code sku}, a stock variant, from one unit on. */
    public static void give(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId, String sku, long unitPrice) {
        UUID price = Identifiers.newId();
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO catalog.customer_price(id, customer_id, sku, currency, note, version, created_at, created_by)
                    VALUES (?, ?, ?, 'VND', 'kept from before 4B-2', 0, now(), 'test')""", price, customerId, sku);
            jdbc.update("""
                    INSERT INTO catalog.customer_price_tier(id, customer_price_id, min_quantity, unit_price, version,
                                                            created_at, created_by)
                    VALUES (?, ?, 1, ?, 0, now(), 'test')""", Identifiers.newId(), price, unitPrice);
        });
    }
}
