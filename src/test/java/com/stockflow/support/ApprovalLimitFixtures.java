package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * Approval limits for tests (kltn-docs 02 §4 step 7, owner decision POLIMIT2, SCRUM-114).
 *
 * <p>A purchase order is approved by someone whose approval limit covers its total, when they have one
 * (no limit configured is no limit, owner decision PO-NOLIMIT). {@code procurement.approval_limits} is keyed by role
 * and a user's roles are the {@code identity.user_role} rows - the role in a test token is not what the service reads -
 * so a fixture gives the user a real role row and the role a limit row, with native SQL like {@link ReferenceRows}.
 * Every fixture makes a role of its own and only ever writes that role's limit rows: the seeded roles' limits are
 * shared by every test on the database and are never deleted or rewritten here. Run these inside a transaction
 * ({@code TransactionTemplate} or {@code @Transactional}).</p>
 */
public final class ApprovalLimitFixtures {

    /** Far above any purchase order a test builds. */
    public static final long UNLIMITED_FOR_TESTS = 1_000_000_000_000L;

    private ApprovalLimitFixtures() {
    }

    /**
     * A new user who may approve any test order: holds a role of their own with a very large VND and USD limit. The
     * role is new, so no test touches a role (or a limit row) another test or the seed relies on.
     */
    public static UUID approver(EntityManager em) {
        UUID user = ReferenceRows.user(em);
        makeApprover(em, user);
        return user;
    }

    /** {@code userId} already exists: it now also holds a new role of its own with the very large limits. */
    public static void makeApprover(EntityManager em, UUID userId) {
        UUID role = newRole(em);
        giveRole(em, userId, role);
        // A limit is per currency, with no conversion: the orders some tests place in USD need a USD limit too.
        setLimit(em, role, "VND", UNLIMITED_FOR_TESTS);
        setLimit(em, role, "USD", UNLIMITED_FOR_TESTS);
    }

    /** A new role that nothing else uses, so its limit is the only one its holders have. */
    public static UUID newRole(EntityManager em) {
        UUID id = Identifiers.newId();
        em.createNativeQuery("""
                        INSERT INTO identity.app_role (id, code, name, version, created_at)
                        VALUES (?1, ?2, 'Approval limit test role', 0, NOW())""")
                .setParameter(1, id)
                .setParameter(2, "LIMIT_" + id.toString().replace("-", "").substring(12).toUpperCase())
                .executeUpdate();
        return id;
    }

    public static void giveRole(EntityManager em, UUID userId, UUID roleId) {
        em.createNativeQuery("""
                        INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                        VALUES (?1, ?2, ?3, 0, NOW()) ON CONFLICT (user_id, role_id) DO NOTHING""")
                .setParameter(1, Identifiers.newId())
                .setParameter(2, userId)
                .setParameter(3, roleId)
                .executeUpdate();
    }

    /**
     * The role's only limit in VND, in force from long ago and open-ended: replaces any it had. For a role made by
     * {@link #newRole}; never pass a seeded role, its rows belong to every test.
     */
    public static void setLimit(EntityManager em, UUID roleId, long vnd) {
        setLimit(em, roleId, "VND", vnd);
    }

    public static void setLimit(EntityManager em, UUID roleId, String currency, long amount) {
        em.createNativeQuery("DELETE FROM procurement.approval_limits WHERE role_id = ?1 AND currency = ?2")
                .setParameter(1, roleId).setParameter(2, currency).executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO procurement.approval_limits (id, role_id, currency, max_po_amount, effective_from,
                                                                 version, created_at)
                        VALUES (?1, ?2, ?3, ?4, DATE '2000-01-01', 0, NOW())""")
                .setParameter(1, Identifiers.newId())
                .setParameter(2, roleId)
                .setParameter(3, currency)
                .setParameter(4, amount)
                .executeUpdate();
    }
}
