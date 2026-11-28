package com.stockflow.customer;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CustomersNeedSalesOwner;
import com.stockflow.contracts.CustomerSalesOwnerChanged;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.contracts.StaffAccountDeactivated;
import com.stockflow.contracts.StaffAccountReactivated;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.ClaimOutcome;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleKind;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.customer.internal.service.StaffDeactivationListener;
import com.stockflow.identity.api.CreateStaffUserCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.UserStatusChange;
import com.stockflow.identity.internal.service.StaffCoverService;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Sales owner of a customer against the real context and database (sales-owner plan 2026-11-28): the one rule for
 * who is responsible ({@link ResponsibleSales}, §2.2), who may act for a customer ({@link SalesOwnership#mayActFor},
 * BR-S12), changes and claims with their history rows and events, and the deactivation of an owner's account (BR-S08)
 * through identity's real status change and the after-commit listener.
 *
 * <p>The database is shared with every other integration test: each test makes its own accounts and customers.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class SalesOwnershipIntegrationTest {

    private static final String REASON = "Reason given by the test";

    @Autowired SalesOwnership ownership;
    @Autowired ResponsibleSales responsible;
    @Autowired SalesStaff salesStaff;
    @Autowired StaffCoverService covers;
    @Autowired StaffDeactivationListener deactivationListener;
    @Autowired IdentityService identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired Clock clock;

    // ---------------------------------------------------------------------------------------------------------------
    // ResponsibleSales (§2.2)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§2.2 OWNER: an active owner with no cover is the one who acts")
    void anActiveOwnerActs() {
        UUID owner = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);

        Responsible answer = responsible.responsibleFor(customer, today()).orElseThrow();

        assertThat(answer.kind()).isEqualTo(ResponsibleKind.OWNER);
        assertThat(answer.ownerId()).isEqualTo(owner);
        assertThat(answer.actingUserId()).isEqualTo(owner);
        assertThat(answer.recipients()).containsExactly(owner);
        assertThat(answer.managerIds()).isEmpty();
    }

    @Test
    @DisplayName("§2.2 / BR-S07 / case 5 COVER: a cover in force today acts for the owner; outside its days the owner does")
    void aCoverInForceActsForTheOwner() {
        UUID owner = staff("SALES_STAFF");
        UUID standIn = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        covers.arrange(new StaffCoverService.ArrangeCover(owner, standIn, today(), today().plusDays(2), "leave"),
                owner, false);

        Responsible during = responsible.responsibleFor(customer, today()).orElseThrow();
        Responsible after = responsible.responsibleFor(customer, today().plusDays(3)).orElseThrow();

        assertThat(during.kind()).isEqualTo(ResponsibleKind.COVER);
        assertThat(during.ownerId()).isEqualTo(owner);
        assertThat(during.actingUserId()).isEqualTo(standIn);
        assertThat(during.recipients()).containsExactly(standIn);
        assertThat(after.kind()).as("the cover ends by itself").isEqualTo(ResponsibleKind.OWNER);
    }

    @Test
    @DisplayName("§2.2 / BR-S08 / case 8 MANAGERS: an owner whose account is locked answers the active managers, at once")
    void anInactiveOwnerFallsBackToTheManagers() {
        UUID owner = staff("SALES_STAFF");
        UUID otherSales = staff("SALES_STAFF");
        UUID manager = staff("SALES_MANAGER");
        UUID customer = customerOwnedBy(owner);
        // Locked behind identity's back: no event, no listener - the state still says ASSIGNED.
        write("UPDATE identity.app_user SET status = 'LOCKED' WHERE id = ?", owner);

        Responsible answer = responsible.responsibleFor(customer, today()).orElseThrow();

        assertThat(ownership.ownerOf(customer).orElseThrow().state()).isEqualTo(SalesOwnerState.ASSIGNED);
        assertThat(answer.kind()).isEqualTo(ResponsibleKind.MANAGERS);
        assertThat(answer.ownerId()).as("the owner who is gone, for the notice").isEqualTo(owner);
        assertThat(answer.actingUserId()).isNull();
        assertThat(answer.needsAssignment()).isTrue();
        assertThat(answer.managerIds()).contains(manager).doesNotContain(owner, otherSales);
        assertThat(answer.recipients()).isEqualTo(answer.managerIds());
    }

    @Test
    @DisplayName("§2.2 / D6: a customer that never had an owner, or whose owner left, answers MANAGERS - never every Sales")
    void unassignedAndLeftBehindAnswerTheManagers() {
        UUID unassigned = customer();
        UUID former = staff("SALES_STAFF");
        UUID leftBehind = customerOwnedBy(former);
        write("UPDATE customer.customer SET sales_owner_state = 'NEEDS_ASSIGNMENT' WHERE id = ?", leftBehind);

        Map<UUID, Responsible> answers = responsible.responsibleFor(List.of(unassigned, leftBehind), today());

        assertThat(answers.get(unassigned).kind()).isEqualTo(ResponsibleKind.MANAGERS);
        assertThat(answers.get(unassigned).ownerId()).isNull();
        assertThat(answers.get(leftBehind).kind()).isEqualTo(ResponsibleKind.MANAGERS);
        assertThat(answers.get(leftBehind).ownerId()).isEqualTo(former);
        List<UUID> salesAgents = salesStaff.activeSalesAgents().stream().map(member -> member.userId())
                .filter(id -> !salesStaff.activeSalesManagers().contains(id)).toList();
        assertThat(answers.get(unassigned).managerIds()).isNotEmpty().doesNotContainAnyElementsOf(salesAgents);
    }

    @Test
    @DisplayName("Case 25: with no active Sales Manager, the System Admins are the last fallback")
    void noManagersFallsBackToTheAdmins() {
        UUID admin = staff("SYSTEM_ADMIN");
        UUID customer = customer();
        // Every manager disabled inside one transaction that is rolled back: the shared database keeps its managers.
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE identity.app_user SET status = 'DISABLED'
                     WHERE id IN (SELECT ur.user_id FROM identity.user_role ur JOIN identity.app_role r
                                    ON r.id = ur.role_id WHERE r.code = 'SALES_MANAGER')""");

            Responsible answer = responsible.responsibleFor(customer, today()).orElseThrow();

            assertThat(answer.kind()).isEqualTo(ResponsibleKind.MANAGERS);
            assertThat(answer.managerIds()).contains(admin);
            assertThat(answer.managerIds()).containsExactlyInAnyOrderElementsOf(salesStaff.activeSystemAdmins());
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("ResponsibleSales: an unknown or null id is left out, never an error")
    void unknownIdsAreLeftOut() {
        UUID owner = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        UUID unknown = UUID.randomUUID();

        Map<UUID, Responsible> answers = responsible.responsibleFor(Arrays.asList(customer, unknown, null), today());

        assertThat(answers).containsOnlyKeys(customer);
        assertThat(responsible.responsibleFor(unknown, today())).isEmpty();
        assertThat(responsible.responsibleFor(List.of(), today())).isEmpty();
    }

    @Test
    @DisplayName("ResponsibleSales is batched: as many statements for twenty-four customers as for twelve of the same "
            + "mix, and no more than four")
    void oneBatchedReadWhateverTheNumberOfCustomers() {
        UUID owner = staff("SALES_STAFF");
        UUID covered = staff("SALES_STAFF");
        UUID standIn = staff("SALES_STAFF");
        // The managers' read answers someone, so the fallback never goes on to read the System Admins (D6, case 25):
        // the shared database holds whatever active managers earlier classes left, possibly none.
        staff("SALES_MANAGER");
        covers.arrange(new StaffCoverService.ArrangeCover(covered, standIn, today(), today(), null), covered, false);
        List<UUID> twentyFour = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            twentyFour.add(customerOwnedBy(owner));
            twentyFour.add(customerOwnedBy(covered));
            twentyFour.add(customer());
        }
        List<UUID> twelve = twentyFour.subList(0, 12);

        long one = statements(List.of(twentyFour.get(0)));
        long many = statements(twelve);
        long twice = statements(twentyFour);

        assertThat(responsible.responsibleFor(twelve, today()).values()).extracting(Responsible::kind)
                .contains(ResponsibleKind.OWNER, ResponsibleKind.COVER, ResponsibleKind.MANAGERS);
        assertThat(twice).as("no statement per customer: twice the customers, the same statements").isEqualTo(many);
        assertThat(many).as("the customers' owners, the owners' accounts, their covers, the managers")
                .isLessThanOrEqualTo(4);
        assertThat(one).as("one customer with a working owner reads no managers").isLessThanOrEqualTo(many);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // mayActFor (BR-S12)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S12 / D1 strict: the owner, the cover in force and a manager act; another salesperson does not")
    void whoMayActForAnOwnedCustomer() {
        UUID owner = staff("SALES_STAFF");
        UUID standIn = staff("SALES_STAFF");
        UUID other = staff("SALES_STAFF");
        UUID manager = staff("SALES_MANAGER");
        UUID customer = customerOwnedBy(owner);
        covers.arrange(new StaffCoverService.ArrangeCover(owner, standIn, today(), today().plusDays(1), null), owner,
                false);

        assertThat(ownership.mayActFor(owner, customer, today())).isTrue();
        assertThat(ownership.mayActFor(standIn, customer, today())).isTrue();
        assertThat(ownership.mayActFor(standIn, customer, today().plusDays(5))).as("after the cover").isFalse();
        assertThat(ownership.mayActFor(manager, customer, today())).isTrue();
        assertThat(ownership.mayActFor(other, customer, today())).isFalse();
        assertThat(ownership.mayActFor(other, UUID.randomUUID(), today())).as("unknown customer").isFalse();
        assertThat(ownership.mayActFor(null, customer, today())).isFalse();
    }

    @Test
    @DisplayName("BR-S12 / case 17: the refusal is CUSTOMER_OWNED_BY_OTHER_SALES naming the owner")
    void theRefusalNamesTheOwner() {
        UUID owner = staff("SALES_STAFF");
        UUID other = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        String ownerName = identity.displayNames(List.of(owner)).get(owner);

        assertThatThrownBy(() -> ownership.requireMayActFor(other, customer, today()))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
                    assertThat(refusal.details()).containsEntry("salesOwnerId", owner)
                            .containsEntry("salesOwnerName", ownerName);
                    assertThat(refusal.messageKey()).isEqualTo("refusal.customer.ownedByOtherSales");
                });
        ownership.requireMayActFor(owner, customer, today());
    }

    @Test
    @DisplayName("BR-S12 / cases 2, 8: a customer with no active owner may be acted for by any salesperson")
    void anyoneActsForACustomerWithNoActiveOwner() {
        UUID other = staff("SALES_STAFF");
        UUID gone = staff("SALES_STAFF");
        UUID unassigned = customer();
        UUID ownerGone = customerOwnedBy(gone);
        write("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?", gone);

        assertThat(ownership.mayActFor(other, unassigned, today())).isTrue();
        assertThat(ownership.mayActFor(other, ownerGone, today())).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // assign and claim
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S01 / BR-S03 / BR-S06: a manager's change writes one history row and publishes the change")
    void aChangeIsRecordedAndPublished() {
        UUID first = staff("SALES_STAFF");
        UUID second = staff("SALES_STAFF");
        UUID manager = staff("SALES_MANAGER");
        UUID customer = customerOwnedBy(first);
        long version = ownership.ownerOf(customer).orElseThrow().version();
        recorded.clear();

        CustomerSalesOwner changed = ownership.assign(new AssignSalesOwnerCommand(customer, second,
                SalesOwnerChangeReason.CUSTOMER_REQUEST, "asked for it", true, version, manager));

        assertThat(changed.salesOwnerId()).isEqualTo(second);
        assertThat(changed.state()).isEqualTo(SalesOwnerState.ASSIGNED);
        assertThat(changed.version()).isGreaterThan(version);
        assertThat(history(customer)).containsExactly(
                "null>" + first + ":ASSIGNED:OTHER:null",
                first + ">" + second + ":ASSIGNED:CUSTOMER_REQUEST:" + manager);
        assertThat(recorded.matching(CustomerSalesOwnerChanged.class, e -> e.customerId().equals(customer)))
                .singleElement().satisfies(event -> {
                    assertThat(event.fromUserId()).isEqualTo(first);
                    assertThat(event.toUserId()).isEqualTo(second);
                    assertThat(event.reason()).isEqualTo(SalesOwnerChangeReason.CUSTOMER_REQUEST);
                    assertThat(event.notifyCustomer()).isTrue();
                    assertThat(event.byUserId()).isEqualTo(manager);
                });
    }

    @Test
    @DisplayName("Case 15: of two managers changing one customer, the later is refused SALES_OWNER_VERSION_CONFLICT")
    void theLaterOfTwoChangesIsRefused() {
        UUID first = staff("SALES_STAFF");
        UUID second = staff("SALES_STAFF");
        UUID third = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(first);
        long seen = ownership.ownerOf(customer).orElseThrow().version();
        ownership.assign(new AssignSalesOwnerCommand(customer, second, SalesOwnerChangeReason.WORKLOAD, null, false,
                seen, null));

        refused(ErrorCode.SALES_OWNER_VERSION_CONFLICT, () -> ownership.assign(new AssignSalesOwnerCommand(customer,
                third, SalesOwnerChangeReason.REGION, null, false, seen, null)));
        assertThat(ownership.ownerOf(customer).orElseThrow().salesOwnerId()).isEqualTo(second);
        assertThat(history(customer)).hasSize(2);
    }

    @Test
    @DisplayName("BR-S04 / D42: an owner is an active SALES_STAFF account - not warehouse staff, an accountant, a locked "
            + "account, a Sales Manager or an admin")
    void onlyAnActiveSalesAgentCanOwn() {
        UUID warehouse = staff("WAREHOUSE_STAFF");
        UUID accountant = staff("ACCOUNTANT");
        UUID locked = staff("SALES_STAFF");
        UUID manager = staff("SALES_MANAGER");
        UUID admin = staff("SYSTEM_ADMIN");
        UUID salesperson = staff("SALES_STAFF");
        write("UPDATE identity.app_user SET status = 'LOCKED' WHERE id = ?", locked);
        UUID customer = customer();

        for (UUID target : List.of(warehouse, accountant, locked, manager, admin)) {
            refused(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE, () -> ownership.assign(new AssignSalesOwnerCommand(customer,
                    target, SalesOwnerChangeReason.WORKLOAD, null, false, 0, null)));
            refused(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE, () -> ownership.claimIfUnowned(customer, target, today()));
        }
        assertThat(ownership.assign(new AssignSalesOwnerCommand(customer, salesperson, SalesOwnerChangeReason.WORKLOAD,
                null, false, 0, null)).salesOwnerId()).isEqualTo(salesperson);
        assertThat(history(customer)).hasSize(1);
    }

    @Test
    @DisplayName("BR-S03: a change without a reason is refused and leaves no history row")
    void aChangeWithoutAReasonIsRefused() {
        UUID first = staff("SALES_STAFF");
        UUID second = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(first);
        long version = ownership.ownerOf(customer).orElseThrow().version();

        refused(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED, () -> ownership.assign(new AssignSalesOwnerCommand(
                customer, second, null, null, false, version, null)));
        refused(ErrorCode.SALES_OWNER_CHANGE_REASON_REQUIRED, () -> ownership.assign(new AssignSalesOwnerCommand(
                customer, second, SalesOwnerChangeReason.OTHER, " ", false, version, null)));
        assertThat(history(customer)).hasSize(1);
        refused(ErrorCode.CUSTOMER_NOT_FOUND, () -> ownership.assign(new AssignSalesOwnerCommand(UUID.randomUUID(),
                second, SalesOwnerChangeReason.WORKLOAD, null, false, 0, null)));
    }

    @Test
    @DisplayName("BR-S05 / D2: claiming a customer with no owner makes the claimer the owner (CLAIMED_FROM_QUEUE)")
    void claimingAnUnownedCustomer() {
        UUID claimer = staff("SALES_STAFF");
        UUID customer = customer();

        assertThat(ownership.claimIfUnowned(customer, claimer, today())).isEqualTo(ClaimOutcome.CLAIMED);
        assertThat(ownership.claimIfUnowned(customer, claimer, today())).isEqualTo(ClaimOutcome.ALREADY_OWNER);

        assertThat(ownership.ownerOf(customer).orElseThrow().salesOwnerId()).isEqualTo(claimer);
        assertThat(history(customer)).containsExactly(
                "null>" + claimer + ":ASSIGNED:CLAIMED_FROM_QUEUE:" + claimer);
    }

    @Test
    @DisplayName("Case 18: a claim of an owned customer is refused CUSTOMER_UNASSIGNED_CLAIM_ONLY; its cover is COVERING")
    void claimingAnOwnedCustomer() {
        UUID owner = staff("SALES_STAFF");
        UUID standIn = staff("SALES_STAFF");
        UUID other = staff("SALES_STAFF");
        UUID warehouse = staff("WAREHOUSE_STAFF");
        UUID customer = customerOwnedBy(owner);
        covers.arrange(new StaffCoverService.ArrangeCover(owner, standIn, today(), today(), null), owner, false);

        refused(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY, () -> ownership.claimIfUnowned(customer, other, today()));
        assertThat(ownership.claimIfUnowned(customer, standIn, today())).isEqualTo(ClaimOutcome.COVERING);
        assertThat(ownership.ownerOf(customer).orElseThrow().salesOwnerId()).isEqualTo(owner);
        assertThat(history(customer)).hasSize(1);
        refused(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE, () -> ownership.claimIfUnowned(customer(), warehouse, today()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Deactivation (BR-S08, BR-S10)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S08 / case 7: locking an owner marks each of their customers NEEDS_ASSIGNMENT, keeps them as the "
            + "former owner, writes one row each and cancels the covers they stood in for")
    void lockingAnOwnerReleasesTheirCustomers() {
        UUID owner = staff("SALES_STAFF");
        UUID colleague = staff("SALES_STAFF");
        UUID first = customerOwnedBy(owner);
        UUID second = customerOwnedBy(owner);
        UUID colleagues = customerOwnedBy(colleague);
        StaffCoverService.CoverView standingIn = covers.arrange(new StaffCoverService.ArrangeCover(colleague, owner,
                today(), today().plusDays(3), null), colleague, false);
        recorded.clear();

        identity.changeUserStatus(owner, UserStatusChange.LOCK, REASON);

        assertThat(jdbc.queryForObject("SELECT status || ':' || cancel_reason FROM identity.staff_cover WHERE id = ?",
                String.class, standingIn.id())).as("cancelled with the lock").isEqualTo("CANCELLED:COVER_DEACTIVATED");
        assertThat(recorded.matching(StaffAccountDeactivated.class, e -> e.userId().equals(owner)))
                .singleElement().extracting(StaffAccountDeactivated::status).isEqualTo("LOCKED");
        Await.until("the owner's customers are released", Duration.ofSeconds(20),
                () -> state(first).equals("NEEDS_ASSIGNMENT") && state(second).equals("NEEDS_ASSIGNMENT"));
        for (UUID customer : List.of(first, second)) {
            CustomerSalesOwner left = ownership.ownerOf(customer).orElseThrow();
            assertThat(left.salesOwnerId()).as("the former owner is kept").isEqualTo(owner);
            assertThat(history(customer)).containsExactly(
                    "null>" + owner + ":ASSIGNED:OTHER:null",
                    owner + ">null:NEEDS_ASSIGNMENT:STAFF_LEFT:null");
        }
        assertThat(state(colleagues)).isEqualTo("ASSIGNED");
        Await.until("the managers' event is out", Duration.ofSeconds(20), () -> !recorded.matching(
                CustomersNeedSalesOwner.class, e -> e.formerUserId().equals(owner)).isEmpty());
        assertThat(recorded.matching(CustomersNeedSalesOwner.class, e -> e.formerUserId().equals(owner)))
                .as("C1: one event for the whole book, not one per customer").singleElement()
                .satisfies(released -> {
                    assertThat(released.customerIds()).containsExactlyInAnyOrder(first, second);
                    assertThat(released.cause()).isEqualTo("LOCKED");
                });
        assertThat(responsible.responsibleFor(colleagues, today()).orElseThrow().kind())
                .as("the cancelled cover no longer acts").isEqualTo(ResponsibleKind.OWNER);

        // Delivered twice: nothing more happens, and the managers are not told again.
        recorded.clear();
        deactivationListener.on(new StaffAccountDeactivated(owner, "LOCKED", null, clock.instant()));
        assertThat(history(first)).hasSize(2);
        assertThat(recorded.matching(CustomersNeedSalesOwner.class, e -> e.formerUserId().equals(owner))).isEmpty();
    }

    @Test
    @DisplayName("D44: locking a salesperson puts their ASSIGNED and CONTACTED leads back to NEW, unassigned, with a "
            + "history row each; a converted or another's lead stays")
    void lockingASalespersonReturnsTheirOpenLeads() {
        UUID salesperson = staff("SALES_STAFF");
        UUID colleague = staff("SALES_STAFF");
        UUID assigned = lead("ASSIGNED", salesperson, null);
        UUID contacted = lead("CONTACTED", salesperson, null);
        UUID converted = lead("CONVERTED", salesperson, customer());
        UUID colleagues = lead("ASSIGNED", colleague, null);

        identity.changeUserStatus(salesperson, UserStatusChange.LOCK, REASON);

        Await.until("the open leads are back in the queue", Duration.ofSeconds(20),
                () -> leadState(assigned).equals("NEW:null") && leadState(contacted).equals("NEW:null"));
        assertThat(leadState(converted)).isEqualTo("CONVERTED:" + salesperson);
        assertThat(leadState(colleagues)).isEqualTo("ASSIGNED:" + colleague);
        assertThat(jdbc.queryForList("""
                SELECT from_status || '>' || to_status || ':' || from_user_id || ':' || (to_user_id IS NULL)
                       || ':' || (note IS NOT NULL)
                  FROM customer.lead_history WHERE lead_id IN (?, ?) ORDER BY from_status""", String.class,
                assigned, contacted)).containsExactly(
                "ASSIGNED>NEW:" + salesperson + ":true:true", "CONTACTED>NEW:" + salesperson + ":true:true");

        deactivationListener.on(new StaffAccountDeactivated(salesperson, "LOCKED", null, clock.instant()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.lead_history WHERE lead_id IN (?, ?)",
                Long.class, assigned, contacted)).as("delivered twice: nothing more").isEqualTo(2);
    }

    @Test
    @DisplayName("BR-S10 / case 13: an account unlocked again gets nothing back by itself")
    void reactivationReturnsNothing() {
        UUID owner = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        identity.changeUserStatus(owner, UserStatusChange.DISABLE, REASON);
        Await.until("the customer is released", Duration.ofSeconds(20),
                () -> state(customer).equals("NEEDS_ASSIGNMENT"));
        recorded.clear();

        identity.changeUserStatus(owner, UserStatusChange.ENABLE, REASON);

        assertThat(recorded.matching(StaffAccountReactivated.class, e -> e.userId().equals(owner))).hasSize(1);
        assertThat(state(customer)).isEqualTo("NEEDS_ASSIGNMENT");
        assertThat(responsible.responsibleFor(customer, today()).orElseThrow().kind())
                .isEqualTo(ResponsibleKind.MANAGERS);
    }

    @Test
    @DisplayName("BR-S08: only a real stop publishes - disabling a locked account says nothing again")
    void onlyARealStopIsPublished() {
        UUID owner = staff("SALES_STAFF");
        identity.changeUserStatus(owner, UserStatusChange.LOCK, REASON);
        recorded.clear();

        identity.changeUserStatus(owner, UserStatusChange.DISABLE, REASON);

        assertThat(recorded.matching(StaffAccountDeactivated.class, e -> e.userId().equals(owner))).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Review findings C2 (lock vs assign), C3 (SALES_STAFF revoked), C4 (lock order)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Finding C2: a lock waits for an assignment in flight, and the listener then releases that customer")
    void aLockWaitsForAnAssignmentInFlight() throws Exception {
        UUID owner = staff("SALES_STAFF");
        UUID customer = customer();
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> assignment = threads.submit(() -> tx.executeWithoutResult(status -> {
                ownership.assign(new AssignSalesOwnerCommand(customer, owner, SalesOwnerChangeReason.OTHER, "race",
                        false, 0, null));
                assigned.countDown();
                await(commit);
            }));
            assertThat(assigned.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> lock = threads.submit(() -> identity.changeUserStatus(owner, UserStatusChange.LOCK, REASON));

            Thread.sleep(1500);
            assertThat(lock.isDone()).as("the lock waits for the assignment's transaction").isFalse();
            commit.countDown();
            assignment.get(20, TimeUnit.SECONDS);
            lock.get(20, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }
        Await.until("the customer assigned in flight is released", Duration.ofSeconds(20),
                () -> state(customer).equals("NEEDS_ASSIGNMENT"));
    }

    @Test
    @DisplayName("Finding C2: an assignment waits for a lock in flight, then refuses the locked account")
    void anAssignmentWaitsForALockInFlight() throws Exception {
        UUID owner = staff("SALES_STAFF");
        UUID customer = customer();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> lock = threads.submit(() -> tx.executeWithoutResult(status -> {
                identity.changeUserStatus(owner, UserStatusChange.LOCK, REASON);
                entityManager.flush();
                locked.countDown();
                await(commit);
            }));
            assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> assignment = threads.submit(() -> ownership.assign(new AssignSalesOwnerCommand(customer, owner,
                    SalesOwnerChangeReason.OTHER, "race", false, 0, null)));

            Thread.sleep(1500);
            assertThat(assignment.isDone()).as("the assignment waits for the lock's transaction").isFalse();
            commit.countDown();
            lock.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> assignment.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                    .cause().extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE);
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }
        assertThat(state(customer)).isEqualTo("UNASSIGNED");
    }

    @Test
    @DisplayName("Finding C3: revoking SALES_STAFF releases the customers and open leads, ends the covers it stood in "
            + "and makes the managers responsible at once; giving it back returns nothing (BR-S10)")
    void revokingSalesStaffReleasesLikeALock() {
        UUID owner = staff("SALES_STAFF");
        identity.assignRole(owner, "WAREHOUSE_STAFF");
        UUID colleague = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        UUID colleagues = customerOwnedBy(colleague);
        UUID lead = lead("ASSIGNED", owner, null);
        covers.arrange(new StaffCoverService.ArrangeCover(colleague, owner, today(), today().plusDays(2), null),
                colleague, false);
        recorded.clear();

        identity.revokeRole(owner, "SALES_STAFF");

        assertThat(recorded.matching(StaffAccountDeactivated.class, e -> e.userId().equals(owner)))
                .singleElement().extracting(StaffAccountDeactivated::status)
                .isEqualTo(StaffAccountDeactivated.SALES_ROLE_REVOKED);
        assertThat(responsible.responsibleFor(colleagues, today()).orElseThrow().kind())
                .as("a stand-in who is no salesperson covers nothing").isEqualTo(ResponsibleKind.OWNER);
        Await.until("the customer and the lead are released", Duration.ofSeconds(20),
                () -> state(customer).equals("NEEDS_ASSIGNMENT") && leadState(lead).equals("NEW:null"));
        assertThat(responsible.responsibleFor(customer, today()).orElseThrow().kind())
                .isEqualTo(ResponsibleKind.MANAGERS);
        assertThat(state(colleagues)).isEqualTo("ASSIGNED");
        recorded.clear();

        identity.assignRole(owner, "SALES_STAFF");

        assertThat(recorded.matching(StaffAccountReactivated.class, e -> e.userId().equals(owner))).hasSize(1);
        assertThat(state(customer)).as("BR-S10: nothing comes back by itself").isEqualTo("NEEDS_ASSIGNMENT");
        assertThat(leadState(lead)).isEqualTo("NEW:null");
    }

    @Test
    @DisplayName("Finding C3: before the listener runs, an owner without SALES_STAFF is not working - the managers "
            + "answer, anyone may act, a claim takes the customer")
    void anOwnerWithoutTheRoleIsNotWorking() {
        UUID owner = staff("SALES_STAFF");
        UUID other = staff("SALES_STAFF");
        UUID customer = customerOwnedBy(owner);
        // Revoked behind identity's back: no event, the customer still says ASSIGNED.
        write("""
                DELETE FROM identity.user_role WHERE user_id = ?
                   AND role_id = (SELECT id FROM identity.app_role WHERE code = 'SALES_STAFF')""", owner);

        assertThat(responsible.responsibleFor(customer, today()).orElseThrow().kind())
                .isEqualTo(ResponsibleKind.MANAGERS);
        assertThat(ownership.mayActFor(other, customer, today())).isTrue();
        assertThat(ownership.claimIfUnowned(customer, other, today())).isEqualTo(ClaimOutcome.CLAIMED);
    }

    @Test
    @DisplayName("Finding C4: the order customers are locked in is PostgreSQL's order of uuids, not UUID.compareTo")
    void customersAreLockedInTheDatabasesOrder() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(UUID.fromString("00000000-0000-7000-8000-000000000000"));
        ids.add(UUID.fromString("f0000000-0000-7000-8000-000000000000"));
        List<UUID> database = jdbc.queryForList("SELECT id FROM unnest(CAST(? AS uuid[])) AS t(id) ORDER BY id",
                UUID.class, (Object) ids.stream().map(UUID::toString).toArray(String[]::new));

        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort(com.stockflow.common.id.Identifiers.DATABASE_ORDER);
        List<UUID> javas = new ArrayList<>(ids);
        javas.sort(UUID::compareTo);

        assertThat(sorted).isEqualTo(database);
        assertThat(javas).as("the order the transfer used before").isNotEqualTo(database);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never let the transaction commit");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Covers (BR-S07) through the service
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S07 / D42: one cover at a time; the stand-in is an active SALES_STAFF account (never a manager); a "
            + "Sales arranges only their own")
    void coverRules() {
        UUID away = staff("SALES_STAFF");
        UUID standIn = staff("SALES_STAFF");
        UUID other = staff("SALES_STAFF");
        UUID warehouse = staff("WAREHOUSE_STAFF");
        UUID manager = staff("SALES_MANAGER");
        covers.arrange(new StaffCoverService.ArrangeCover(away, standIn, today(), today().plusDays(4), null), away,
                false);

        refused(ErrorCode.STAFF_COVER_OVERLAP, () -> covers.arrange(new StaffCoverService.ArrangeCover(away, other,
                today().plusDays(4), today().plusDays(6), null), away, false));
        refused(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE, () -> covers.arrange(new StaffCoverService.ArrangeCover(other,
                warehouse, today(), today(), null), other, false));
        refused(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE, () -> covers.arrange(new StaffCoverService.ArrangeCover(other,
                manager, today(), today(), null), other, false));
        refused(ErrorCode.FORBIDDEN, () -> covers.arrange(new StaffCoverService.ArrangeCover(other, standIn, today(),
                today(), null), away, false));
        refused(ErrorCode.STAFF_COVER_TOO_LONG, () -> covers.arrange(new StaffCoverService.ArrangeCover(other, standIn,
                today(), today().plusDays(60), null), other, false));
        assertThat(covers.arrange(new StaffCoverService.ArrangeCover(away, other, today().plusDays(5),
                today().plusDays(6), null), null, true).status()).as("a manager, the day after").isEqualTo("ACTIVE");
        assertThat(covers.list(away, true)).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private UUID staff(String role) {
        String name = "so." + UUID.randomUUID().toString().substring(0, 8);
        return identity.createStaffUser(new CreateStaffUserCommand(name, name + "@example.com", "Sales " + name, null,
                List.of(role), null)).user().userId();
    }

    /** Hikari runs without auto-commit: a write outside a transaction would be rolled back. */
    private void write(String sql, Object... values) {
        tx.executeWithoutResult(status -> jdbc.update(sql, values));
    }

    private UUID lead(String status, UUID sales, UUID convertedCustomer) {
        UUID id = UUID.randomUUID();
        write("""
                INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, status,
                                           assigned_to, assigned_at, contacted_at, converted_customer_id, converted_at,
                                           created_at)
                VALUES (?, 'Lead Co', 'Contact', '+84901234567', NOW(), NOW(), ?, ?, NOW(),
                        CASE WHEN ? IN ('CONTACTED', 'CONVERTED') THEN NOW() END, ?,
                        CASE WHEN ? = 'CONVERTED' THEN NOW() END, NOW())""",
                id, status, sales, status, convertedCustomer, status);
        return id;
    }

    private String leadState(UUID lead) {
        return jdbc.queryForObject("SELECT status || ':' || coalesce(assigned_to::text, 'null') FROM customer.lead "
                + "WHERE id = ?", String.class, lead);
    }

    private UUID customer() {
        return tx.execute(status -> ReferenceRows.customer(entityManager));
    }

    private UUID customerOwnedBy(UUID owner) {
        UUID customer = customer();
        ownership.assign(new AssignSalesOwnerCommand(customer, owner, SalesOwnerChangeReason.OTHER, "test owner", false,
                0, null));
        return customer;
    }

    private String state(UUID customer) {
        return jdbc.queryForObject("SELECT sales_owner_state FROM customer.customer WHERE id = ?", String.class,
                customer);
    }

    /** {@code from>to:state:reason:changedBy}, oldest first. */
    private List<String> history(UUID customer) {
        return jdbc.queryForList("""
                SELECT coalesce(from_user_id::text, 'null') || '>' || coalesce(to_user_id::text, 'null') || ':'
                       || to_state || ':' || reason || ':' || coalesce(changed_by::text, 'null')
                  FROM customer.customer_sales_owner_history WHERE customer_id = ? ORDER BY changed_at, id""",
                String.class, customer);
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    /**
     * The statements one call prepares, counted on its own session only. The session factory's statistics are not
     * used: they count every thread's statements, and the owner changes this test makes have the async listeners
     * (chat's conversations following the owner, the event registry marking each publication complete) still running
     * when the counting starts. Right after the setup a call that takes three statements counted six or seven; a
     * slower runner kept that up through every run and failed the bound with five.
     */
    private long statements(List<UUID> customers) {
        return tx.execute(status -> {
            AtomicLong prepared = new AtomicLong();
            entityManager.unwrap(Session.class).addEventListeners(new SessionEventListener() {
                @Override
                public void jdbcPrepareStatementStart() {
                    prepared.incrementAndGet();
                }
            });
            responsible.responsibleFor(customers, today());
            status.setRollbackOnly();
            return prepared.get();
        });
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.errorCode()).isEqualTo(code));
    }
}
