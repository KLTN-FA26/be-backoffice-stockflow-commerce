package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The approval limit that applied to a decision: the {@code procurement.approval_limits} row that was in force, the
 * most it allowed, and the role it belongs to. A decision records all three, so that when the row is later closed or
 * replaced an audit still knows which one allowed what (kltn-docs 02 §4 step 7, owner decision SCRUM-124 rule 1).
 *
 * @param id     the {@code approval_limits} row
 * @param roleId the role the row belongs to: the one of the person's roles whose limit was the highest
 * @param amount the most a purchase order may total, after tax, in the currency of the row
 */
public record ApprovalLimit(UUID id, UUID roleId, Money amount) {

    public ApprovalLimit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(amount, "amount");
    }

    /**
     * The one rule for whether a configured limit covers a total, after tax: within it, and a limit of zero or less
     * covers nothing. Every decision asks it - an order approved at submission or by a person, an amendment of a
     * standard order or a subcontract supplement, automatically or by a person - so a limit means the same everywhere.
     * Having no limit at all is not a limit of zero and never reaches here (owner decision PO-NOLIMIT): the caller asks
     * only when one is configured.
     */
    public static boolean covers(Money limit, Money total) {
        return limit.amount().signum() > 0 && total.compareTo(limit) <= 0;
    }

    /** {@link #covers(Money, Money)} with this limit's amount. */
    public boolean covers(Money total) {
        return covers(amount, total);
    }

    /**
     * What the timeline event of an automatic approval carries about the decision: the order's total, the limit that
     * allowed it (row, amount, role) and whose limit it was; the same facts the approval record has as columns.
     */
    public Map<String, String> facts(Money total, UUID limitHolderId) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("autoApproved", "true");
        facts.put("total", total.amount().toPlainString());
        facts.put("limit", amount.amount().toPlainString());
        facts.put("currency", amount.currency().getCurrencyCode());
        facts.put("limitId", id.toString());
        facts.put("limitRoleId", roleId.toString());
        facts.put("limitHolderId", limitHolderId.toString());
        return facts;
    }
}
