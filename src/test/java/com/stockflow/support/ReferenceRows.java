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
}
