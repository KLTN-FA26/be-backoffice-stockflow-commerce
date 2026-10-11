package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * Minimal rows for the tables other modules point at, for persistence tests on an empty database.
 *
 * <p>Since ADR-0007 a reference to another module's row is a foreign key: a design draft's
 * {@code customer_id}, its owner and reviewer, a customer's {@code user_id} must exist. A test that
 * invents {@code Identifiers.newId()} for them is refused at flush. These insert the smallest valid
 * row with native SQL, so a test of one module does not need another module's service wired up.
 * The user cannot sign in: its password hash is not a hash.</p>
 */
public final class ReferenceRows {

    private ReferenceRows() {
    }

    /** An {@code identity.app_user} row; returns its id. */
    public static UUID user(EntityManager em) {
        UUID id = Identifiers.newId();
        em.createNativeQuery("""
                        INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
                        VALUES (?1, ?2, ?3, '!test-no-login', 'ACTIVE', 0, NOW())""")
                .setParameter(1, id)
                .setParameter(2, "test-" + id)
                .setParameter(3, "test-" + id + "@example.com")
                .executeUpdate();
        return id;
    }

    /** A {@code customer.customer} row with no account; returns its id. */
    public static UUID customer(EntityManager em) {
        UUID id = Identifiers.newId();
        em.createNativeQuery("""
                        INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                        VALUES (?1, 'Test customer', ?2, 'ACTIVE', 0, NOW())""")
                .setParameter(1, id)
                .setParameter(2, "customer-" + id + "@example.com")
                .executeUpdate();
        return id;
    }

    /** A product and its default variant, as product creation leaves them. */
    public record ProductRow(UUID productId, UUID variantId, String sku) {
    }

    /**
     * A DRAFT {@code product.products} row with its default ACTIVE variant; the variant insert creates
     * the SKU's inventory item (trigger). Since C1, a design draft's {@code product_id} and an order
     * line's {@code sku} are foreign keys to these.
     */
    public static ProductRow product(EntityManager em) {
        UUID productId = Identifiers.newId();
        UUID variantId = Identifiers.newId();
        String code = "T" + productId.toString().replace("-", "").substring(20).toUpperCase();
        em.createNativeQuery("""
                        INSERT INTO product.products (id, code, name, slug, status, version, created_at)
                        VALUES (?1, ?2, 'Test product', ?3, 'DRAFT', 0, NOW())""")
                .setParameter(1, productId)
                .setParameter(2, code)
                .setParameter(3, code.toLowerCase())
                .executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO product.variants (id, product_id, sku, name, status, is_default,
                                                      attribute_signature, position, version, created_at)
                        VALUES (?1, ?2, ?3, 'Test product', 'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())""")
                .setParameter(1, variantId)
                .setParameter(2, productId)
                .setParameter(3, code)
                .executeUpdate();
        return new ProductRow(productId, variantId, code);
    }
}
