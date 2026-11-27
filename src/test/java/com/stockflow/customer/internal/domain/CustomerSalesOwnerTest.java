package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Sales owner on the customer aggregate (sales-owner plan 2026-11-28): every transition of
 * UNASSIGNED / ASSIGNED / NEEDS_ASSIGNMENT, the reason rule (BR-S03) and the version check (case 15). The same rules are
 * stated in the database and pinned by {@code tools/db/qa/99_sales_owner.sql}.
 */
class CustomerSalesOwnerTest {

    private static final Instant T0 = Instant.parse("2026-11-28T03:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    @Test
    @DisplayName("§2.1: a customer starts UNASSIGNED, with neither owner nor date")
    void startsUnassigned() {
        Customer customer = customer(SalesOwner.unassigned(), 0);

        assertThat(customer.salesOwner().state()).isEqualTo(SalesOwnerState.UNASSIGNED);
        assertThat(customer.salesOwner().userId()).isNull();
        assertThat(customer.salesOwner().since()).isNull();
    }

    @Test
    @DisplayName("BR-S02 / case 1: UNASSIGNED -> ASSIGNED, the change records from nobody to the owner")
    void unassignedToAssigned() {
        Customer customer = customer(SalesOwner.unassigned(), 0);

        SalesOwnerChange change = customer.assignSalesOwner(A, SalesOwnerChangeReason.CREATED_BY_SALES, null, 0, A, T0,
                null).orElseThrow();

        assertThat(customer.salesOwner()).isEqualTo(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0));
        assertThat(change.fromUserId()).isNull();
        assertThat(change.toUserId()).isEqualTo(A);
        assertThat(change.toState()).isEqualTo(SalesOwnerState.ASSIGNED);
        assertThat(change.reason()).isEqualTo(SalesOwnerChangeReason.CREATED_BY_SALES);
        assertThat(change.changedBy()).isEqualTo(A);
        assertThat(change.customerId()).isEqualTo(customer.id());
    }

    @Test
    @DisplayName("BR-S01 / BR-S03: ASSIGNED -> ASSIGNED to another, with reason, note and the batch")
    void assignedToAnother() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 4);

        SalesOwnerChange change = customer.assignSalesOwner(B, SalesOwnerChangeReason.WORKLOAD, "  rebalancing  ", 4,
                MANAGER, T1, null).orElseThrow();

        assertThat(customer.salesOwner()).isEqualTo(new SalesOwner(B, SalesOwnerState.ASSIGNED, T1));
        assertThat(change.fromUserId()).isEqualTo(A);
        assertThat(change.toUserId()).isEqualTo(B);
        assertThat(change.note()).isEqualTo("rebalancing");
        assertThat(change.changedBy()).isEqualTo(MANAGER);
    }

    @Test
    @DisplayName("BR-S01: assigning the owner a customer already has changes nothing and records nothing")
    void assigningTheSameOwnerIsANoOp() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 2);

        assertThat(customer.assignSalesOwner(A, SalesOwnerChangeReason.WORKLOAD, null, 2, MANAGER, T1, null)).isEmpty();
        assertThat(customer.salesOwner().since()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Case 15: a change against a stale version is refused SALES_OWNER_VERSION_CONFLICT and changes nothing")
    void staleVersionIsRefused() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 5);

        refused(ErrorCode.SALES_OWNER_VERSION_CONFLICT,
                () -> customer.assignSalesOwner(B, SalesOwnerChangeReason.WORKLOAD, null, 4, MANAGER, T1, null));
        assertThat(customer.salesOwner().userId()).isEqualTo(A);
    }

    @Test
    @DisplayName("BR-S03: no reason, or OTHER without a note, is refused SALES_OWNER_CHANGE_REASON_REQUIRED")
    void reasonIsRequired() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 0);

        refused(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED,
                () -> customer.assignSalesOwner(B, null, "why not", 0, MANAGER, T1, null));
        refused(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED,
                () -> customer.assignSalesOwner(B, SalesOwnerChangeReason.OTHER, "   ", 0, MANAGER, T1, null));
        refused(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED,
                () -> customer.transferSalesOwner(A, B, null, null, MANAGER, T1, null));
        assertThat(customer.salesOwner().userId()).isEqualTo(A);

        assertThat(customer.assignSalesOwner(B, SalesOwnerChangeReason.OTHER, "moved to the north", 0, MANAGER, T1,
                null)).isPresent();
    }

    @Test
    @DisplayName("BR-S01: a change names the new owner")
    void aNewOwnerIsRequired() {
        Customer customer = customer(SalesOwner.unassigned(), 0);

        refused(ErrorCode.VALIDATION_FAILED,
                () -> customer.assignSalesOwner(null, SalesOwnerChangeReason.WORKLOAD, null, 0, MANAGER, T1, null));
    }

    @Test
    @DisplayName("BR-S08: ASSIGNED -> NEEDS_ASSIGNMENT keeps the former owner and its date; a second mark changes nothing")
    void assignedToNeedsAssignment() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 0);

        SalesOwnerChange change = customer.markNeedsSalesOwner(A, null, T1).orElseThrow();

        assertThat(customer.salesOwner()).isEqualTo(new SalesOwner(A, SalesOwnerState.NEEDS_ASSIGNMENT, T0));
        assertThat(change.fromUserId()).isEqualTo(A);
        assertThat(change.toUserId()).isNull();
        assertThat(change.toState()).isEqualTo(SalesOwnerState.NEEDS_ASSIGNMENT);
        assertThat(change.reason()).isEqualTo(SalesOwnerChangeReason.STAFF_LEFT);
        assertThat(change.changedBy()).isNull();
        assertThat(customer.markNeedsSalesOwner(A, null, T1)).as("a retried event").isEmpty();
    }

    @Test
    @DisplayName("BR-S08: marking for an account that is not the assigned owner changes nothing")
    void markingSomeoneElsesCustomerChangesNothing() {
        Customer owned = customer(new SalesOwner(B, SalesOwnerState.ASSIGNED, T0), 0);
        Customer unowned = customer(SalesOwner.unassigned(), 0);

        assertThat(owned.markNeedsSalesOwner(A, null, T1)).isEmpty();
        assertThat(unowned.markNeedsSalesOwner(A, null, T1)).isEmpty();
        assertThat(owned.salesOwner().state()).isEqualTo(SalesOwnerState.ASSIGNED);
    }

    @Test
    @DisplayName("BR-S10 / case 13: NEEDS_ASSIGNMENT -> ASSIGNED only by a manager's assignment, even back to the former owner")
    void needsAssignmentToAssigned() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.NEEDS_ASSIGNMENT, T0), 3);

        SalesOwnerChange change = customer.assignSalesOwner(A, SalesOwnerChangeReason.OTHER, "back from leave", 3,
                MANAGER, T1, null).orElseThrow();

        assertThat(customer.salesOwner()).isEqualTo(new SalesOwner(A, SalesOwnerState.ASSIGNED, T1));
        assertThat(change.fromUserId()).isEqualTo(A);
        assertThat(change.toUserId()).isEqualTo(A);
    }

    @Test
    @DisplayName("BR-S09: a transfer moves the source's customers, assigned or left behind, with the batch")
    void transferMovesTheSourcesCustomers() {
        UUID batch = UUID.randomUUID();
        Customer assigned = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 0);
        Customer leftBehind = customer(new SalesOwner(A, SalesOwnerState.NEEDS_ASSIGNMENT, T0), 0);

        SalesOwnerChange first = assigned.transferSalesOwner(A, B, SalesOwnerChangeReason.STAFF_LEFT, null, MANAGER, T1,
                batch).orElseThrow();
        SalesOwnerChange second = leftBehind.transferSalesOwner(A, B, SalesOwnerChangeReason.STAFF_LEFT, null, MANAGER,
                T1, batch).orElseThrow();

        assertThat(List.of(assigned.salesOwner(), leftBehind.salesOwner()))
                .containsOnly(new SalesOwner(B, SalesOwnerState.ASSIGNED, T1));
        assertThat(List.of(first.batchId(), second.batchId())).containsOnly(batch);
    }

    @Test
    @DisplayName("BR-S09 / case 9: a customer no longer the source's is skipped, not overwritten")
    void transferSkipsACustomerChangedMeanwhile() {
        Customer movedMeanwhile = customer(new SalesOwner(MANAGER, SalesOwnerState.ASSIGNED, T0), 0);
        Customer unowned = customer(SalesOwner.unassigned(), 0);
        Customer alreadyThere = customer(new SalesOwner(B, SalesOwnerState.ASSIGNED, T0), 0);

        assertThat(movedMeanwhile.transferSalesOwner(A, B, SalesOwnerChangeReason.WORKLOAD, null, MANAGER, T1, null))
                .isEmpty();
        assertThat(unowned.transferSalesOwner(A, B, SalesOwnerChangeReason.WORKLOAD, null, MANAGER, T1, null)).isEmpty();
        assertThat(alreadyThere.transferSalesOwner(B, B, SalesOwnerChangeReason.WORKLOAD, null, MANAGER, T1, null))
                .isEmpty();
        assertThat(movedMeanwhile.salesOwner().userId()).isEqualTo(MANAGER);
    }

    @Test
    @DisplayName("BR-S05 / D2: UNASSIGNED or NEEDS_ASSIGNMENT -> ASSIGNED to the claimer, reason CLAIMED_FROM_QUEUE")
    void claimOfACustomerWithNoOwner() {
        Customer unowned = customer(SalesOwner.unassigned(), 0);
        Customer leftBehind = customer(new SalesOwner(A, SalesOwnerState.NEEDS_ASSIGNMENT, T0), 0);

        SalesOwnerChange claimed = unowned.claimIfUnowned(B, false, T1).orElseThrow();
        leftBehind.claimIfUnowned(B, false, T1).orElseThrow();

        assertThat(claimed.reason()).isEqualTo(SalesOwnerChangeReason.CLAIMED_FROM_QUEUE);
        assertThat(claimed.changedBy()).isEqualTo(B);
        assertThat(List.of(unowned.salesOwner(), leftBehind.salesOwner()))
                .containsOnly(new SalesOwner(B, SalesOwnerState.ASSIGNED, T1));
    }

    @Test
    @DisplayName("BR-S05: an owner whose account no longer works counts as none, so the customer can be claimed")
    void claimOfACustomerWhoseOwnerIsInactive() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 0);

        assertThat(customer.claimIfUnowned(B, false, T1)).isPresent();
        assertThat(customer.salesOwner().userId()).isEqualTo(B);
    }

    @Test
    @DisplayName("Case 18: claiming a customer another active salesperson owns is refused CUSTOMER_UNASSIGNED_CLAIM_ONLY")
    void claimOfAnOwnedCustomerIsRefused() {
        Customer customer = customer(new SalesOwner(A, SalesOwnerState.ASSIGNED, T0), 0);

        refused(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY, () -> customer.claimIfUnowned(B, true, T1));
        assertThat(customer.salesOwner().userId()).isEqualTo(A);
        assertThat(customer.claimIfUnowned(A, true, T1)).as("the owner claiming their own").isEmpty();
    }

    @Test
    @DisplayName("§2.1: the state agrees with the owner and the date, as ck_customer_sales_owner_consistent says")
    void stateAgreesWithItsColumns() {
        assertThatThrownBy(() -> new SalesOwner(A, SalesOwnerState.UNASSIGNED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SalesOwner(null, SalesOwnerState.ASSIGNED, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SalesOwner(A, SalesOwnerState.NEEDS_ASSIGNMENT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Customer customer(SalesOwner owner, long version) {
        return new Customer(UUID.randomUUID(), UUID.randomUUID(), "Cong ty A", "a@example.com", null, null, null,
                CustomerStatus.ACTIVE, List.of(), owner, version, T0);
    }

    private static void refused(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.errorCode()).isEqualTo(code));
    }
}
