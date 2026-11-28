package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lead's status machine (plan §10.7) and the request to change salesperson (§10.5, D12), without a database. */
class LeadAndOwnerChangeRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-28T03:00:00Z");
    private final UUID manager = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();

    @Test
    @DisplayName("§10.7: NEW → ASSIGNED → CONTACTED → CONVERTED, each step a history row")
    void aLeadMovesForward() {
        Lead lead = Lead.submit(form(), NOW).lead();

        LeadChange assigned = lead.assign(sales, manager, NOW).orElseThrow();
        assertThat(assigned.fromStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(assigned.toUserId()).isEqualTo(sales);
        assertThat(lead.assign(sales, manager, NOW)).as("the same Sales again changes nothing").isEmpty();
        assertThat(lead.markContacted(" called ", sales, NOW).note()).isEqualTo("called");
        UUID customer = UUID.randomUUID();
        lead.convert(customer, sales, NOW);

        assertThat(lead.status()).isEqualTo(LeadStatus.CONVERTED);
        assertThat(lead.convertedCustomerId()).isEqualTo(customer);
        refused(ErrorCode.LEAD_ALREADY_HANDLED, () -> lead.disqualify("late", manager, NOW));
        refused(ErrorCode.LEAD_ALREADY_HANDLED, () -> lead.assign(UUID.randomUUID(), manager, NOW));
    }

    @Test
    @DisplayName("§10.7: a NEW lead cannot be contacted or converted; disqualifying needs a reason; reassigning keeps CONTACTED")
    void aLeadsRefusals() {
        Lead lead = Lead.submit(form(), NOW).lead();
        refused(ErrorCode.LEAD_NOT_ASSIGNED, () -> lead.markContacted(null, sales, NOW));
        refused(ErrorCode.LEAD_NOT_ASSIGNED, () -> lead.convert(UUID.randomUUID(), sales, NOW));
        refused(ErrorCode.VALIDATION_FAILED, () -> lead.disqualify("  ", manager, NOW));

        lead.assign(sales, manager, NOW);
        lead.markContacted(null, sales, NOW);
        refused(ErrorCode.LEAD_ALREADY_HANDLED, () -> lead.markContacted("again", sales, NOW));
        UUID other = UUID.randomUUID();
        LeadChange moved = lead.assign(other, manager, NOW).orElseThrow();
        assertThat(moved.fromStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(moved.toStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(moved.fromUserId()).isEqualTo(sales);

        LeadChange closed = lead.disqualify(" not B2B ", manager, NOW);
        assertThat(closed.toStatus()).isEqualTo(LeadStatus.DISQUALIFIED);
        assertThat(lead.disqualifiedReason()).isEqualTo("not B2B");
    }

    @Test
    @DisplayName("§10.7: a lead is reachable - a phone or an e-mail")
    void aLeadNeedsAContact() {
        refused(ErrorCode.VALIDATION_FAILED, () -> new Lead.Form("Co", null, "A", null, null, null, null, List.of(),
                null, null));
    }

    @Test
    @DisplayName("§10.5 / D12: one PENDING at a time, then 30 days after a decision")
    void oneRequestAtATimeThenACooldown() {
        UUID customer = UUID.randomUUID();
        OwnerChangeRequest pending = OwnerChangeRequest.ask(customer, UUID.randomUUID(), sales,
                OwnerChangeRequest.Reason.SLOW_RESPONSE, null, null, NOW);
        OwnerChangeRequest.requireMayAskAfter(null, NOW);
        refused(ErrorCode.OWNER_CHANGE_REQUEST_PENDING, () -> OwnerChangeRequest.requireMayAskAfter(pending, NOW));

        pending.reject("stays", manager, NOW);
        refused(ErrorCode.OWNER_CHANGE_REQUEST_COOLDOWN,
                () -> OwnerChangeRequest.requireMayAskAfter(pending, NOW.plus(Duration.ofDays(29))));
        OwnerChangeRequest.requireMayAskAfter(pending, NOW.plus(OwnerChangeRequest.COOLDOWN));
        refused(ErrorCode.OWNER_CHANGE_REQUEST_ALREADY_DECIDED, () -> pending.approve(sales, null, manager, NOW));
    }

    @Test
    @DisplayName("§10.5: a request has a reason and never names the current owner; a rejection says why; approval names the owner")
    void aRequestsRules() {
        UUID customer = UUID.randomUUID();
        refused(ErrorCode.VALIDATION_FAILED, () -> OwnerChangeRequest.ask(customer, null, sales, null, null, null, NOW));
        refused(ErrorCode.VALIDATION_FAILED, () -> OwnerChangeRequest.ask(customer, null, sales,
                OwnerChangeRequest.Reason.OTHER, null, sales, NOW));
        OwnerChangeRequest request = OwnerChangeRequest.ask(customer, null, sales, OwnerChangeRequest.Reason.OTHER,
                " ", UUID.randomUUID(), NOW);
        assertThat(request.note()).isNull();
        refused(ErrorCode.VALIDATION_FAILED, () -> request.reject(" ", manager, NOW));
        refused(ErrorCode.VALIDATION_FAILED, () -> request.approve(null, null, manager, NOW));
        UUID next = UUID.randomUUID();
        request.approve(next, "ok", manager, NOW);
        assertThat(request.status()).isEqualTo(OwnerChangeRequest.Status.APPROVED);
        assertThat(request.newSalesOwnerId()).isEqualTo(next);
    }

    private static Lead.Form form() {
        return new Lead.Form("Co", "0312345678", "A", null, "+84912345678", null, null,
                List.of(Lead.Interest.CUP, Lead.Interest.CUP), null, null);
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.errorCode()).isEqualTo(code));
    }
}
