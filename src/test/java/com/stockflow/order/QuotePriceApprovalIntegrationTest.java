package com.stockflow.order;

import com.stockflow.catalog.internal.service.CustomerPriceService;
import com.stockflow.catalog.internal.service.StandardPriceService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 14 BR-10 and 13 BR-04 as changed on 2026-11-26, against a real PostgreSQL with every migration: a quote whose
 * price breaks are measured against their reference - the customer's own price, else the standard price of the variant
 * the SKU was drawn on - and that waits for the Sales Manager when a break is more than 10% below it, or has none.
 *
 * <p>What is pinned: the quote stays a DRAFT while it waits, so the customer never sees it; it is locked until approved
 * (sent at once, as the Sales account that asked), rejected (back to Sales with the reason) or withdrawn; whoever asked
 * does not decide; a cancellation closes the waiting review; and every submission keeps the breaks it was decided on.
 * Security is off in this context: the caller is passed in, as the controller does; who holds the approval is pinned by
 * {@code PermissionMatrixTest}.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.order.quote.price-approval-percent=10"})
class QuotePriceApprovalIntegrationTest {

    private static final int PER_CARTON = 10;

    @Autowired Quotes quotes;
    @Autowired StandardPriceService standardPrices;
    @Autowired CustomerPriceService customerPrices;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private CurrentUser sales;
    private CurrentUser manager;
    private CurrentUser portal;
    private UUID customerId;
    private String sku;
    /** The variant the customer's SKU was drawn on: its standard price is the reference when the customer has none. */
    private String variant;

    @BeforeEach
    void setUp() {
        sales = staff(Role.SALES_STAFF);
        manager = staff(Role.SALES_MANAGER);
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly B', ?, '0901234567', 'ACTIVE', 0, NOW())""",
                customerId, portalUser, "buyer-" + customerId + "@example.com"));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, PER_CARTON).sku();
        variant = jdbc.queryForObject("""
                SELECT s.sku FROM product.customer_skus c JOIN product.variants s ON s.id = c.variant_id
                 WHERE c.sku = ?""", String.class, sku);
        standardPrice(1000);   // from 1 carton, 1 000 VND a piece
    }

    private CurrentUser staff(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name().toLowerCase() + "-" + id, Set.of(role), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    private void standardPrice(int unitPrice) {
        long version = standardPrices.get(variant).version();
        standardPrices.replace(variant, List.of(new StandardPriceService.TierInput(1, BigDecimal.valueOf(unitPrice))),
                null, version);
    }

    private Quotes.QuoteView drafted(int unitPrice) {
        return quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, new Quotes.Draft(
                Instant.now().plus(7, ChronoUnit.DAYS), null,
                List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(1, BigDecimal.valueOf(unitPrice))))))));
    }

    private Quotes.QuoteView sent(Quotes.QuoteView quote) {
        return quotes.send(sales, quote.id(), quote.version());
    }

    private String reviewOutcome(UUID quoteId) {
        return jdbc.queryForObject("SELECT outcome FROM ordering.quote_price_review WHERE quote_id = ? "
                + "ORDER BY submitted_at DESC LIMIT 1", String.class, quoteId);
    }

    @Test
    void aQuoteWithinTheThresholdIsSentStraight() {
        Quotes.QuoteView quote = sent(drafted(950));   // 5% below the standard price

        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.priceReviewPending()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote_price_review WHERE quote_id = ?",
                Integer.class, quote.id())).isZero();
    }

    @Test
    void aQuoteTooFarBelowWaitsHiddenAndLockedUntilAnotherPersonApprovesIt() {
        Quotes.QuoteView drafted = drafted(850);   // 15% below
        assertThat(drafted.priceCheck().needsApproval()).isTrue();
        assertThat(drafted.priceCheck().lines().getFirst().tiers().getFirst().source()).isEqualTo("STANDARD_PRICE");
        assertThat(drafted.priceCheck().lines().getFirst().tiers().getFirst().discountPercent())
                .isEqualByComparingTo("15.00");

        Quotes.QuoteView waiting = sent(drafted);

        assertThat(waiting.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(waiting.priceReviewPending()).isTrue();
        assertThat(waiting.priceReview().outcome()).isEqualTo("PENDING");
        assertThat(waiting.priceReview().lines().getFirst().tiers().getFirst().referencePrice())
                .isEqualByComparingTo("1000");
        // The customer never sees a draft: not this one either.
        assertThatThrownBy(() -> quotes.get(portal, waiting.id())).isInstanceOf(NotFoundException.class);
        assertThat(quotes.list(portal, new Quotes.Criteria(null, null, null, 0, 20, null)).items()).isEmpty();
        // The Sales Manager's queue lists it.
        assertThat(quotes.list(manager, new Quotes.Criteria(customerId, null, null, 0, 20, null, true)).items())
                .extracting(Quotes.Row::id).containsExactly(waiting.id());
        // Locked: neither edited nor sent again while it waits.
        assertThatThrownBy(() -> quotes.edit(sales, waiting.id(), waiting.version(), new Quotes.Draft(
                Instant.now().plus(7, ChronoUnit.DAYS), null,
                List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(1, BigDecimal.valueOf(990))))))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_PENDING);
        // Whoever asked does not decide.
        assertThatThrownBy(() -> quotes.approvePrice(sales, waiting.id(), waiting.version(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_SELF);

        Quotes.QuoteView approved = quotes.approvePrice(manager, waiting.id(), waiting.version(), "Khach lon, dong y");

        assertThat(approved.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(approved.priceReviewPending()).isFalse();
        assertThat(approved.sentBy()).isEqualTo(sales.userId());
        assertThat(approved.priceReview().outcome()).isEqualTo("APPROVED");
        assertThat(approved.priceReview().decidedBy()).isEqualTo(manager.userId());
        assertThat(approved.priceReview().reason()).isEqualTo("Khach lon, dong y");
        assertThat(quotes.get(portal, approved.id()).status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void aRejectionHandsTheDraftBackWithItsReason() {
        Quotes.QuoteView waiting = sent(drafted(800));

        Quotes.QuoteView rejected = quotes.rejectPrice(manager, waiting.id(), waiting.version(), "Gia qua thap");

        assertThat(rejected.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(rejected.priceReviewPending()).isFalse();
        assertThat(rejected.priceReview().outcome()).isEqualTo("REJECTED");
        assertThat(rejected.priceReview().reason()).isEqualTo("Gia qua thap");
        assertThatThrownBy(() -> quotes.rejectPrice(manager, rejected.id(), rejected.version(), " "))
                .isInstanceOf(BusinessException.class);
        // Back with Sales: edited, then sent within the threshold.
        Quotes.QuoteView edited = quotes.edit(sales, rejected.id(), rejected.version(), new Quotes.Draft(
                Instant.now().plus(7, ChronoUnit.DAYS), null,
                List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(1, BigDecimal.valueOf(1000)))))));
        assertThat(sent(edited).status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void salesWithdrawsAndACancellationClosesTheWaitingReview() {
        Quotes.QuoteView waiting = sent(drafted(800));

        Quotes.QuoteView withdrawn = quotes.withdrawPriceReview(sales, waiting.id(), waiting.version());
        assertThat(withdrawn.priceReviewPending()).isFalse();
        assertThat(reviewOutcome(waiting.id())).isEqualTo("WITHDRAWN");

        Quotes.QuoteView again = sent(withdrawn);
        assertThat(again.priceReviewPending()).isTrue();
        Quotes.QuoteView cancelled = quotes.cancel(sales, again.id(), again.version(), "Khach khong mua nua");

        assertThat(cancelled.status()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(cancelled.priceReviewPending()).isFalse();
        assertThat(reviewOutcome(waiting.id())).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote_price_review WHERE quote_id = ? "
                + "AND outcome = 'PENDING'", Integer.class, waiting.id())).isZero();
    }

    @Test
    void withNoReferenceAtAllTheSalesManagerLooks() {
        standardPrices.delete(variant, standardPrices.get(variant).version());

        Quotes.QuoteView waiting = sent(drafted(5000));   // well above anything: there is nothing to measure against

        assertThat(waiting.priceReviewPending()).isTrue();
        assertThat(waiting.priceReview().lines().getFirst().tiers().getFirst().referencePrice()).isNull();
        assertThat(waiting.priceReview().lines().getFirst().tiers().getFirst().needsApproval()).isTrue();
    }

    @Test
    void theCustomersOwnPriceIsTheReferenceBeforeTheStandardPrice() {
        customerPrices.create(customerId, sku, List.of(new CustomerPriceService.TierInput(1, BigDecimal.valueOf(2000))),
                null);

        Quotes.QuoteView waiting = sent(drafted(1500));   // 50% above the standard price, 25% below the customer's own

        assertThat(waiting.priceReviewPending()).isTrue();
        assertThat(waiting.priceReview().lines().getFirst().tiers().getFirst().source()).isEqualTo("CUSTOMER_PRICE");
        assertThat(waiting.priceReview().lines().getFirst().tiers().getFirst().discountPercent())
                .isEqualByComparingTo("25.00");
    }

    @Test
    void aLineWithoutTiersStartsFromTheStandardPrice() {
        standardPrice(1200);

        Quotes.QuoteView quote = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, new Quotes.Draft(
                Instant.now().plus(7, ChronoUnit.DAYS), null, List.of(new Quotes.Line(sku, 3, List.of())))));

        assertThat(quote.lines().getFirst().tiers()).extracting(Quotes.Tier::unitPrice)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("1200"));
        assertThat(quote.priceCheck().needsApproval()).isFalse();
    }
}
