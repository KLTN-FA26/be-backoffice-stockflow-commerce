package com.stockflow.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who may look after customers, and who stands behind them (sales-owner plan BR-S04, BR-S16, §2.2). Read by
 * {@code customer} to validate an owner or a cover and to name the fallback recipients, and by the modules that route
 * work to a customer's salesperson.
 *
 * <p>A <b>Sales agent</b> is an ACTIVE account holding SALES_STAFF, never one that also holds CUSTOMER (owner decision
 * D42 of 2026-11-28: a Sales Manager or a System Admin coordinates but never owns a customer or covers for anyone; it
 * narrows BR-S16). Asked of the role, not of a permission: who is "a salesperson" is the business's org chart, and an
 * administrator who holds every permission is not one. The database states the same rule in the triggers of
 * {@code customer.customer} and {@code identity.staff_cover}. Like
 * {@link IdentityService#activeStaff}, nothing here reveals an e-mail, a phone or why an account stopped working.</p>
 */
public interface SalesStaff {

    /** The role of a salesperson who may own customers and cover (D42). */
    Set<String> SALES_ROLES = Set.of("SALES_STAFF");

    /** Whether the account is a Sales agent now: ACTIVE, SALES_STAFF, not a portal customer. False for null or unknown. */
    boolean isActiveSalesAgent(UUID userId);

    /**
     * {@link #isActiveSalesAgent}, with the account read {@code FOR SHARE} in the caller's transaction (which must exist):
     * until it ends, no lock, disable or revoke of SALES_STAFF of that account can commit, since each of those takes the
     * account's row {@code FOR UPDATE} first. For a write that makes the account responsible for something (an owner, a
     * cover, a lead's Sales): without it, a lock committed between this check and the write left customers ASSIGNED to
     * a locked account for good, because the after-commit listener that releases them had already run (finding C2).
     * The database triggers on {@code customer.customer} and {@code identity.staff_cover} take the same lock.
     */
    boolean lockActiveSalesAgent(UUID userId);

    /**
     * Which of the accounts are Sales agents now ({@link #isActiveSalesAgent}), in one statement; unknown and null ids
     * are left out. An owner who lost SALES_STAFF is no more working than a locked one (finding C3).
     */
    Set<UUID> activeSalesAgentsAmong(Collection<UUID> userIds);

    /** Every Sales agent, by name, with their roles. One query; bounded by the staff of the business. */
    List<StaffMember> activeSalesAgents();

    /** The ACTIVE Sales Managers, by name: who is told when a customer has no active owner (D6). */
    List<UUID> activeSalesManagers();

    /** The ACTIVE System Admins, by name: the last fallback when no Sales Manager is active (plan §4 case 25). */
    List<UUID> activeSystemAdmins();

    /**
     * Whether the account is ACTIVE and one of its roles grants {@code permission} ({@code resource:ACTION}). For a
     * rule that depends on who acts rather than on the request's own token (a manager acting for anyone).
     */
    boolean holdsPermission(UUID userId, String permission);
}
