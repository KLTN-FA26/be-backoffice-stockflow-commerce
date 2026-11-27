package com.stockflow.order;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.internal.service.OrderOperations;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.SalesWorkloadService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SalesOwners;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sales-owner plan (2026-11-28) on the order side, against the real context and database: strict mode on quotes
 * and contracts (kltn-docs 14 BR-25, §4.4 step 18; plan BR-S12), the owner recorded on the documents drawn up (14
 * BR-26), the operational work never blocked but recorded "làm hộ" and told to the owner (17 BR-32, plan BR-S13), and
 * the Sales Managers' workload board (plan §10.4). Who is who is set in the database: an owner is an ACTIVE SALES_STAFF
 * account (D42) given the customer through {@code customer :: api}, as a manager assigns one.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class SalesOwnerOrderIntegrationTest {

    private static final long PRICE = 40_000;

    @Autowired Quotes quotes;
    @Autowired SalesContracts contracts;
    @Autowired OrderOperations operations;
    @Autowired SalesWorkloadService workload;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    private SalesOwners owners;
    private ConfirmedOrders confirmedOrders;
    /** The customer's owner: a plain salesperson (SALES_STAFF). */
    private CurrentUser owner;
    private CurrentUser other;
    private CurrentUser manager;
    private UUID customer;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void setUp() {
        owners = new SalesOwners(context);
        confirmedOrders = new ConfirmedOrders(context);
        // The fixture's own salesperson draws up the orders the operational tests need; it is a manager (strict mode
        // never blocks one), so the owner here is a plain salesperson of the test's own.
        owner = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        other = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        manager = SalesOwners.caller(owners.account("SALES_MANAGER"), Role.SALES_MANAGER);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        owners.own(customer, owner.userId());
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 10);
        confirmedOrders.legalProfile(customer);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    private Quotes.Create draftFor(UUID customerId, String code) {
        return new Quotes.Create(UUID.randomUUID(), customerId, new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)),
                null, List.of(new Quotes.Line(code, 3, List.of(new Quotes.Tier(1, new BigDecimal("3000")))))));
    }

    /** One delivery batch of every carton, opening today, paid on signing - as ConfirmedOrders draws one. */
    private SalesContracts.Draft draftOf(UUID orderId) {
        List<SalesContracts.BatchLine> lines = jdbc.queryForList("SELECT id, cartons FROM ordering.order_line WHERE "
                + "order_id = ? ORDER BY id", orderId).stream().map(row -> new SalesContracts.BatchLine(
                (UUID) row.get("id"), ((Number) row.get("cartons")).intValue())).toList();
        return new SalesContracts.Draft(null, List.of(new SalesContracts.Batch(today(), today().plusDays(2), lines)),
                ConfirmedOrders.paidOnSigning());
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private static void refused(ThrowingCallable step) {
        assertThatThrownBy(step).as("14 BR-25: someone else's customer")
                .extracting(SalesOwnerOrderIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
    }

    private UUID quoteOwner(UUID quoteId) {
        return jdbc.queryForObject("SELECT sales_owner_id FROM ordering.quote WHERE id = ?", UUID.class, quoteId);
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    private <T> T as(CurrentUser user, java.util.function.Supplier<T> call) {
        return WithCurrentUser.call(user, DataScope.ALL, call);
    }

    private List<java.util.Map<String, Object>> onBehalf(UUID orderId) {
        return jdbc.queryForList("SELECT action, actor_id, acting_for_user_id, id FROM ordering.order_on_behalf_action "
                + "WHERE order_id = ? ORDER BY acted_at, id", orderId);
    }

    private long notices(UUID user, String dedupeKey) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? "
                + "AND dedupe_key = ?", Long.class, user, dedupeKey);
    }

    // ---- strict mode: quotes --------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-25 / case 17: another salesperson drafts no quote for an owned customer; the refusal names the owner")
    void anotherSalespersonIsRefused() {
        assertThatThrownBy(() -> quotes.create(other, draftFor(customer, sku.sku())))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
                    assertThat(refusal.details()).containsEntry("salesOwnerId", owner.userId());
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id = ?", Long.class,
                customer)).isZero();
    }

    @Test
    @DisplayName("BR-25: the owner, the owner's cover of the day and a Sales Manager may draft; BR-26: each draft "
            + "records the owner")
    void theOwnerTheCoverAndAManagerMayDraft() {
        CurrentUser cover = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        owners.cover(owner.userId(), cover.userId(), today());

        for (CurrentUser allowed : List.of(owner, cover, manager)) {
            Quotes.QuoteView drafted = quotes.create(allowed, draftFor(customer, sku.sku()));
            assertThat(quoteOwner(drafted.id())).as("14 BR-26: the owner of the moment, whoever drafts")
                    .isEqualTo(owner.userId());
        }
    }

    @Test
    @DisplayName("BR-25, D43: a customer with no owner - anyone drafts, nobody is recorded, and drafting claims nobody")
    void anUnownedCustomerIsAnyonesAndNobodyClaimsIt() {
        UUID unowned = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued itsSku = CustomerSkuRows.issue(jdbc, tx, entityManager, unowned, 10);

        Quotes.QuoteView drafted = quotes.create(other, draftFor(unowned, itsSku.sku()));

        assertThat(quoteOwner(drafted.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT sales_owner_state FROM customer.customer WHERE id = ?", String.class,
                unowned)).as("D43: only a manager, a chat claim, creating the customer or a lead make an owner")
                .isEqualTo("UNASSIGNED");
    }

    @Test
    @DisplayName("BR-25 / case 11, case 27: once the customer moves, the old owner may no longer touch its quote, the "
            + "new one may; the quote keeps the owner it was drawn up under")
    void aTransferMovesWhoMayActNotWhoseSaleItWas() {
        Quotes.QuoteView drafted = quotes.create(owner, draftFor(customer, sku.sku()));
        refused(() -> quotes.send(other, drafted.id(), drafted.version()));
        refused(() -> quotes.cancel(other, drafted.id(), drafted.version(), "not ours"));

        owners.own(customer, other.userId());

        refused(() -> quotes.cancel(owner, drafted.id(), drafted.version(), "lost the customer"));
        Quotes.QuoteView cancelled = quotes.cancel(other, drafted.id(), drafted.version(), "the new owner tidies up");
        assertThat(cancelled.status()).isEqualTo(com.stockflow.order.internal.domain.QuoteStatus.CANCELLED);
        assertThat(quoteOwner(drafted.id())).as("BR-26: the sale stays the old owner's").isEqualTo(owner.userId());
    }

    @Test
    @DisplayName("BR-25 / case 4: a locked owner leaves the customer to anyone (NEEDS_ASSIGNMENT in effect)")
    void aLockedOwnerBlocksNobody() {
        owners.lock(owner.userId());

        Quotes.QuoteView drafted = quotes.create(other, draftFor(customer, sku.sku()));

        assertThat(drafted.status()).isEqualTo(com.stockflow.order.internal.domain.QuoteStatus.DRAFT);
    }

    // ---- strict mode: contracts -----------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-25: another salesperson draws up, edits, sends or cancels no contract of the customer; the owner "
            + "draws it up and it records the owner (BR-26); recording it signed is not checked")
    void contractsFollowTheOwner() {
        UUID order = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
        refused(() -> as(other, () -> contracts.create(other, order, draftOf(order))));

        SalesContracts.ContractView drafted = as(owner, () -> contracts.create(owner, order, draftOf(order)));
        assertThat(jdbc.queryForObject("SELECT sales_owner_id FROM ordering.sales_contract WHERE id = ?", UUID.class,
                drafted.id())).isEqualTo(owner.userId());
        refused(() -> as(other, () -> contracts.edit(other, order, drafted.version(), draftOf(order))));
        refused(() -> as(other, () -> contracts.send(other, order, drafted.version())));
        refused(() -> as(other, () -> contracts.cancel(other, order, drafted.version(), "not ours")));

        SalesContracts.ContractView sent = as(owner, () -> contracts.send(owner, order, drafted.version()));
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = as(other, () -> contracts.sign(other, order, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today()));
        assertThat(signed.status()).as("14 §4.4 step 18: recording the signature is not checked").isEqualTo("SIGNED");
    }

    @Test
    @DisplayName("BR-25 (review fix 1): only the owner, the cover and a manager withdraw a contract's credit review; "
            + "another salesperson is refused and the review keeps waiting")
    void creditReviewWithdrawalFollowsTheOwner() {
        // 1,000,000 of credit: a contract of 4,000,000 paid after delivery waits for accounting when sent (15 BR-11).
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                    approved_at)
                VALUES (?, ?, 1000000, 30, ?, NOW())""", com.stockflow.common.id.Identifiers.newId(), customer,
                manager.userId()));
        CurrentUser cover = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        owners.cover(owner.userId(), cover.userId(), today());
        UUID order = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
        List<SalesContracts.Installment> afterDelivery = List.of(new SalesContracts.Installment("After delivery",
                "AFTER_DELIVERY", null, new BigDecimal("100"), null, null));
        SalesContracts.Draft draft = new SalesContracts.Draft(null, draftOf(order).deliverySchedule(), afterDelivery);
        SalesContracts.ContractView drafted = as(owner, () -> contracts.create(owner, order, draft));

        for (CurrentUser allowed : List.of(owner, cover, manager)) {
            long version = jdbc.queryForObject("SELECT version FROM ordering.sales_contract WHERE id = ?", Long.class,
                    drafted.id());
            SalesContracts.ContractView waiting = as(owner, () -> contracts.send(owner, order, version));
            assertThat(waiting.creditReview()).isEqualTo("PENDING");

            refused(() -> as(other, () -> contracts.withdrawCreditReview(other, order, waiting.version())));
            assertThat(jdbc.queryForObject("SELECT credit_review FROM ordering.sales_contract WHERE id = ?",
                    String.class, drafted.id())).as("still waiting for accounting").isEqualTo("PENDING");

            SalesContracts.ContractView withdrawn = as(allowed, () -> contracts.withdrawCreditReview(allowed, order,
                    waiting.version()));
            assertThat(withdrawn.creditReview()).isEqualTo("NONE");
        }
    }

    // ---- BR-32: operational work done for another ------------------------------------------------------------------

    @Test
    @DisplayName("17 BR-32 / case 22: another salesperson chooses the warehouse - not blocked, recorded 'làm hộ' in "
            + "the order's history, the owner told once; the owner choosing again records nothing")
    void operationalWorkForAnotherIsRecordedAndTold() {
        ConfirmedOrders.Confirmed order = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 10, PRICE));
        UUID hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);

        as(other, () -> operations.chooseShippingWarehouse(order.orderId(), hcm, other.userId()));

        List<java.util.Map<String, Object>> entries = onBehalf(order.orderId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.get("action")).isEqualTo("CHOOSE_SHIPPING_WAREHOUSE");
            assertThat(entry.get("actor_id")).isEqualTo(other.userId());
            assertThat(entry.get("acting_for_user_id")).isEqualTo(owner.userId());
        });
        assertThat(notices(owner.userId(), "order-on-behalf:" + entries.getFirst().get("id"))).isEqualTo(1);
        assertThat(operations.onBehalfHistory(order.orderId())).singleElement()
                .satisfies(entry -> assertThat(entry.actingForUserId()).isEqualTo(owner.userId()));

        as(owner, () -> operations.chooseShippingWarehouse(order.orderId(), hcm, owner.userId()));
        assertThat(onBehalf(order.orderId())).as("the owner's own work").hasSize(1);
    }

    @Test
    @DisplayName("17 BR-32: the cover holds the order - their own work; a manager lifts the hold - 'làm hộ', told to "
            + "the cover of the day")
    void theCoverIsNotDoingItForSomeoneElse() {
        ConfirmedOrders.Confirmed order = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 10, PRICE));
        CurrentUser cover = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        owners.cover(owner.userId(), cover.userId(), today());

        as(cover, () -> operations.putOnHold(order.orderId(), HoldReason.CUSTOMER, "customer asked", cover.userId()));
        assertThat(onBehalf(order.orderId())).isEmpty();

        as(manager, () -> operations.resolveHold(order.orderId(), "back on track", manager.userId()));
        assertThat(onBehalf(order.orderId())).singleElement().satisfies(entry -> {
            assertThat(entry.get("action")).isEqualTo("RESOLVE_HOLD");
            assertThat(entry.get("acting_for_user_id")).as("the cover acts for the owner today").isEqualTo(cover.userId());
        });
    }

    @Test
    @DisplayName("17 BR-32: a customer with no active owner - the entry names who did it, nobody is told")
    void noOwnerNobodyToTell() {
        ConfirmedOrders.Confirmed order = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 10, PRICE));
        owners.lock(owner.userId());

        as(other, () -> operations.putOnHold(order.orderId(), HoldReason.CUSTOMER, "customer asked", other.userId()));

        assertThat(onBehalf(order.orderId())).singleElement().satisfies(entry -> {
            assertThat(entry.get("actor_id")).isEqualTo(other.userId());
            assertThat(entry.get("acting_for_user_id")).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE category = "
                + "'ORDER_ON_BEHALF' AND source_id = ?", Long.class, order.orderId().toString())).isZero();
    }

    // ---- the workload board ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("§10.4: one row per active salesperson - customers, open quotes, contracts to sign, sales of 30 days by "
            + "the owner recorded on the contract; no manager row")
    void theWorkloadBoard() {
        ConfirmedOrders.Confirmed order = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 10, PRICE));
        quotes.create(owner, draftFor(customer, sku.sku()));
        BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM ordering.customer_order WHERE id = ?",
                BigDecimal.class, order.orderId());

        List<SalesWorkloadService.Row> rows = workload.workload();

        SalesWorkloadService.Row mine = rows.stream().filter(r -> r.userId().equals(owner.userId())).findFirst()
                .orElseThrow();
        assertThat(mine.customers()).isEqualTo(1);
        assertThat(mine.customersWithOpenOrders()).isEqualTo(1);
        assertThat(mine.openQuotes()).isEqualTo(1);
        assertThat(mine.contractsAwaitingSignature()).isZero();
        assertThat(mine.revenue30d()).as("BR-26: the contract recorded them when drawn up").isEqualByComparingTo(total);
        assertThat(mine.newCustomersThisMonth()).as("given to them by the test today").isEqualTo(1);
        assertThat(mine.onLeave()).isFalse();
        assertThat(mine.paused()).isFalse();
        assertThat(rows).extracting(SalesWorkloadService.Row::userId).contains(other.userId())
                .doesNotContain(manager.userId());
    }
}
