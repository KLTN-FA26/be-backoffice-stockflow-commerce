package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnership;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

/**
 * Salespeople, managers and owned customers for the tests of the sales-owner plan (2026-11-28) on the order side: an
 * account holding a role in the database (an owner must be an ACTIVE SALES_STAFF, D42 - the customer table's trigger
 * says so), and a customer given an owner through {@code customer :: api}, the way a manager assigns one. Each call
 * makes new rows, so tests never share an owner.
 */
public final class SalesOwners {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;
    private final SalesOwnership ownership;

    public SalesOwners(ApplicationContext context) {
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.tx = context.getBean(TransactionTemplate.class);
        this.entityManager = context.getBean(EntityManager.class);
        this.ownership = context.getBean(SalesOwnership.class);
    }

    /** A new ACTIVE account holding {@code role} in the database. */
    public UUID account(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        grant(user, role);
        return user;
    }

    /** Gives an existing account {@code role} in the database. */
    public void grant(UUID user, String role) {
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO identity.user_role (id, user_id, role_id, version, "
                + "created_at) SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", Identifiers.newId(),
                user, role));
    }

    /** A new ACTIVE salesperson (SALES_STAFF). */
    public UUID salesperson() {
        return account("SALES_STAFF");
    }

    /** The caller of a service for {@code userId}, holding {@code role}. */
    public static CurrentUser caller(UUID userId, Role role) {
        return new CurrentUser(userId, role.name().toLowerCase() + "-" + userId, Set.of(role), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    /** Gives {@code customerId} to {@code owner}, as a manager would (reason OTHER, with a note). */
    public void own(UUID customerId, UUID owner) {
        long version = ownership.ownerOf(customerId).orElseThrow().version();
        tx.executeWithoutResult(s -> ownership.assign(new AssignSalesOwnerCommand(customerId, owner,
                SalesOwnerChangeReason.OTHER, "set by the test", false, version, null)));
    }

    /** {@code standIn} covers for {@code owner} today and tomorrow. */
    public void cover(UUID owner, UUID standIn, java.time.LocalDate from) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.staff_cover (id, user_id, cover_user_id, from_date, to_date, reason, status,
                                                  arranged_by, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'test', 'ACTIVE', ?, 0, NOW())""", Identifiers.newId(), owner, standIn, from,
                from.plusDays(1), owner));
    }

    /** Locks the account (as an admin would, without the events: the owner just stops working). */
    public void lock(UUID user) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = 'LOCKED' WHERE id = ?", user));
    }
}
