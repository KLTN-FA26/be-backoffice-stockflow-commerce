package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.ContractCreditReviews;
import com.stockflow.order.internal.domain.SalesFollowUps;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the people inside whose turn it is on a contract or an order (kltn-docs 14 §6, WF-02 FLOW-1..4), from what the
 * orders look like now - an hourly scan, never an event - so a late, repeated or lost event changes nothing and orders
 * already stuck are found too:
 * <ul>
 *   <li>a contract sent and not signed for {@code stockflow.order.follow-up.unsigned-contract-after}, an order signed and
 *       waiting for the installment on signing for {@code stockflow.order.follow-up.unpaid-order-after} - three days
 *       each unless configured - to its salesperson;</li>
 *   <li>FLOW-1a: an order held for credit, to whoever may decide it ({@value #DECIDES_CREDIT});</li>
 *   <li>15 BR-11 (2026-11-26): a contract waiting for accounting before it is sent for longer than
 *       {@code stockflow.order.follow-up.credit-review-after} - a day unless configured - to whoever may decide it,
 *       whoever asked excepted;</li>
 *   <li>FLOW-2: an order confirmed with no shipping warehouse chosen, to its salesperson;</li>
 *   <li>FLOW-3: an order held because a voided receipt left its installment on signing short, to its salesperson - and
 *       again once that installment is paid again: Sales lifts the hold, nothing here does (docs 15). A PAYMENT hold
 *       a coordinator raised by hand is theirs to lift, and is not reported.</li>
 * </ul>
 * FLOW-1b, accounting's credit decision, is told as it is made ({@link #creditDecided}), as are a contract put in front
 * of accounting before it is sent and what accounting decided on it ({@link #contractCreditReviewRequested},
 * {@link #contractCreditDecided}). Nothing is cancelled, expired
 * or moved here: whether to chase the customer or cancel stays Sales' call.
 *
 * <p>One inbox notice per step ({@code dedupeKey}, a hold's by when it was raised): the inbox posts a key to a recipient
 * once, so the hourly run tells nobody twice, and a run that died half way is simply run again. "Its salesperson" is
 * whoever looks after the order's customer today (sales-owner plan, 2026-11-28; 00 §6, 15 BR-22): the owner, the
 * owner's cover, or - with no active owner - the Sales Managers, the notice marked "cần phân công"
 * ({@link SalesRecipients}). Never every salesperson. A customer moved to another salesperson is followed up with the
 * new one from the next run (14 §4.4 step 20). System text only: numbers, codes and days - never what anybody
 * typed.</p>
 */
@Service
@Transactional
public class SalesFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(SalesFollowUpService.class);

    static final String CATEGORY = "SALES_FOLLOW_UP";
    /** Who decides an order held for credit (15 §4.3 step 10), as the role matrix says. */
    static final String DECIDES_CREDIT = "sales-order-credit-holds:APPROVE";
    /** More than a sales team leaves waiting at once; the newest first, so the ones just past the line always fit. */
    static final int BATCH_SIZE = 200;
    /** Where accounting decides credit: the orders held at signing and the contracts waiting before sending. */
    static final String CREDIT_REVIEWS_PAGE = "/admin/orders/credit-holds";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesFollowUps followUps;
    private final Inbox inbox;
    private final SalesRecipients recipients;
    private final IdentityService identity;
    private final PaymentService payments;
    private final Clock clock;
    private final Duration unsignedAfter;
    private final Duration unpaidAfter;
    private final ContractCreditReviews creditReviews;
    private final Duration creditReviewAfter;

    SalesFollowUpService(SalesFollowUps followUps, Inbox inbox, SalesRecipients recipients, IdentityService identity,
                         PaymentService payments, Clock clock,
                         @Value("${stockflow.order.follow-up.unsigned-contract-after:P3D}") Duration unsignedAfter,
                         @Value("${stockflow.order.follow-up.unpaid-order-after:P3D}") Duration unpaidAfter,
                         ContractCreditReviews creditReviews,
                         @Value("${stockflow.order.follow-up.credit-review-after:PT24H}") Duration creditReviewAfter) {
        if (unsignedAfter.isNegative() || unsignedAfter.isZero() || unpaidAfter.isNegative() || unpaidAfter.isZero()
                || creditReviewAfter.isNegative() || creditReviewAfter.isZero()) {
            throw new IllegalArgumentException("stockflow.order.follow-up.* must be positive durations");
        }
        this.creditReviews = creditReviews;
        this.creditReviewAfter = creditReviewAfter;
        this.followUps = followUps;
        this.inbox = inbox;
        this.recipients = recipients;
        this.identity = identity;
        this.payments = payments;
        this.clock = clock;
        this.unsignedAfter = unsignedAfter;
        this.unpaidAfter = unpaidAfter;
    }

    /**
     * What one run found waiting, and how many recipients were told for the first time.
     *
     * @param paymentHolds orders held for payment by a voided receipt, of which {@code paidAgain} have their installment on signing paid
     * @param creditReviews contracts waiting for accounting before they are sent for longer than the line (15 BR-11)
     */
    public record Reminded(int unsignedContracts, int unpaidOrders, int creditHolds, int confirmedUnplaced,
                           int paymentHolds, int paidAgain, int creditReviews, int newlyTold) {
    }

    /** Posts the notices due now; those already posted are not posted again. */
    public Reminded remind() {
        Instant now = clock.instant();
        ResourceBundle texts = texts();
        List<SalesFollowUps.UnsignedContract> unsigned = followUps.unsignedSince(now.minus(unsignedAfter), BATCH_SIZE);
        List<SalesFollowUps.UnpaidOrder> unpaid = followUps.unpaidSince(now.minus(unpaidAfter), BATCH_SIZE);
        List<SalesFollowUps.WaitingOrder> creditHeld = followUps.heldFor(OrderServiceImpl.CREDIT_HOLD, BATCH_SIZE);
        List<SalesFollowUps.WaitingOrder> unplaced = followUps.confirmedWithoutWarehouse(BATCH_SIZE);
        List<SalesFollowUps.WaitingOrder> paymentHeld = followUps.heldAfterVoidedReceipt(BATCH_SIZE);
        // Who looks after each customer, asked once for the whole run (a handful of statements, not one per order).
        SalesRecipients.Chooser sales = recipients.chooser().load(java.util.stream.Stream.of(
                        unsigned.stream().map(SalesFollowUps.UnsignedContract::customerId),
                        unpaid.stream().map(SalesFollowUps.UnpaidOrder::customerId),
                        unplaced.stream().map(SalesFollowUps.WaitingOrder::customerId),
                        paymentHeld.stream().map(SalesFollowUps.WaitingOrder::customerId))
                .flatMap(ids -> ids).toList());
        List<ContractCreditReviews.Waiting> reviewsWaiting = creditReviews.waitingSince(now.minus(creditReviewAfter),
                BATCH_SIZE);
        int told = 0;
        for (SalesFollowUps.UnsignedContract contract : unsigned) {
            told += post(sales.of(contract.customerId()), texts, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.contract-unsigned.title")
                            .formatted(contract.contractNumber(), unsignedAfter.toDays()),
                    texts.getString("inbox.sales.follow-up.contract-unsigned.body").formatted(contract.contractNumber(),
                            contract.orderNumber(), DAY.format(BusinessCalendar.date(contract.sentAt()))),
                    "/admin/orders/" + contract.orderId(), "SALES_CONTRACT", contract.contractId(),
                    "contract-unsigned:" + contract.contractId());
        }
        for (SalesFollowUps.UnpaidOrder order : unpaid) {
            told += post(sales.of(order.customerId()), texts, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.order-unpaid.title")
                            .formatted(order.orderNumber(), unpaidAfter.toDays()),
                    texts.getString("inbox.sales.follow-up.order-unpaid.body").formatted(order.orderNumber(),
                            order.contractNumber(), DAY.format(BusinessCalendar.date(order.signedAt()))),
                    "/admin/orders/" + order.orderId(), "ORDER", order.orderId(), "order-unpaid:" + order.contractId());
        }
        told += creditHolds(creditHeld, texts);
        told += creditReviewsWaiting(reviewsWaiting, texts);
        for (SalesFollowUps.WaitingOrder order : unplaced) {
            told += post(sales.of(order.customerId()), texts, InboxSeverity.INFO,
                    texts.getString("inbox.sales.follow-up.confirmed.title").formatted(order.orderNumber()),
                    texts.getString("inbox.sales.follow-up.confirmed.body").formatted(order.orderNumber()),
                    "/admin/orders/" + order.orderId(), "ORDER", order.orderId(), "confirmed:" + order.orderId());
        }
        Map<UUID, InstallmentStanding> standings = paymentHeld.isEmpty() ? Map.of()
                : payments.installmentStandings(paymentHeld.stream().map(SalesFollowUps.WaitingOrder::orderId).toList());
        int paidAgain = 0;
        for (SalesFollowUps.WaitingOrder order : paymentHeld) {
            SalesRecipients.Recipients to = sales.of(order.customerId());
            String day = DAY.format(BusinessCalendar.date(order.since()));
            told += post(to, texts, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.payment-hold.title").formatted(order.orderNumber()),
                    texts.getString("inbox.sales.follow-up.payment-hold.body").formatted(order.orderNumber(), day),
                    "/admin/orders/" + order.orderId(), "ORDER", order.orderId(),
                    "payment-hold:%s:%s".formatted(order.orderId(), order.since()));
            InstallmentStanding standing = standings.get(order.orderId());
            if (standing != null && standing.opened() && standing.onSigningPaid()) {
                paidAgain++;
                told += post(to, texts, InboxSeverity.INFO,
                        texts.getString("inbox.sales.follow-up.payment-restored.title").formatted(order.orderNumber()),
                        texts.getString("inbox.sales.follow-up.payment-restored.body").formatted(order.orderNumber(),
                                day),
                        "/admin/orders/" + order.orderId(), "ORDER", order.orderId(),
                        "payment-restored:%s:%s".formatted(order.orderId(), order.since()));
            }
        }
        if (told > 0) {
            log.info("Sales follow-up: {} contract(s) unsigned, {} order(s) unpaid, {} held for credit, {} confirmed "
                            + "without a warehouse, {} held for payment ({} paid again), {} contract(s) waiting for "
                            + "accounting, {} notice(s) posted",
                    unsigned.size(), unpaid.size(), creditHeld.size(), unplaced.size(), paymentHeld.size(), paidAgain,
                    reviewsWaiting.size(), told);
        }
        return new Reminded(unsigned.size(), unpaid.size(), creditHeld.size(), unplaced.size(), paymentHeld.size(),
                paidAgain, reviewsWaiting.size(), told);
    }

    /**
     * 15 BR-11 (2026-11-26): a contract waits for accounting before it is sent - whoever may decide credit is told now,
     * in the sending transaction, whoever asked excepted (they may not decide it). System text only: the numbers and
     * why the check failed.
     *
     * @param checkOutcome {@code OVERDUE} or {@code OVER_LIMIT}
     */
    void contractCreditReviewRequested(UUID reviewId, UUID orderId, String orderNumber, String contractNumber,
                                       String checkOutcome, UUID submittedBy) {
        List<UUID> deciders = decidersOtherThan(submittedBy);
        if (deciders.isEmpty()) {
            log.warn("Contract {} waits for accounting and nobody else holds {} - nobody was told", contractNumber,
                    DECIDES_CREDIT);
            return;
        }
        ResourceBundle texts = texts();
        inbox.post(deciders, new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.sales.follow-up.contract-credit-review.title").formatted(contractNumber),
                texts.getString("inbox.sales.follow-up.contract-credit-review.body").formatted(contractNumber,
                        orderNumber, texts.getString("inbox.sales.follow-up.contract-credit-review.reason."
                                + checkOutcome)),
                CREDIT_REVIEWS_PAGE, "ORDER", orderId.toString(),
                "contract-credit-review:%s:requested".formatted(reviewId)));
    }

    /**
     * 15 BR-11: accounting decided a contract waiting before it is sent - the Sales account that asked is told now, in
     * the same transaction as the decision, and whoever looks after the customer when that is someone else (a customer
     * moved meanwhile; 14 §4.4 step 20, as for a quote's prices). Only the outcome: the note or the reason is on the
     * contract.
     *
     * @param salesUserId who sent the contract for accounting's decision
     */
    void contractCreditDecided(UUID reviewId, UUID orderId, String orderNumber, String contractNumber,
                               UUID customerId, UUID salesUserId, boolean approved) {
        ResourceBundle texts = texts();
        String outcome = approved ? "contract-credit-approved" : "contract-credit-refused";
        SalesRecipients.Recipients to = recipients.chooser().of(customerId).withFirst(salesUserId);
        post(to, texts, approved ? InboxSeverity.INFO : InboxSeverity.WARN,
                texts.getString("inbox.sales.follow-up." + outcome + ".title").formatted(contractNumber),
                texts.getString("inbox.sales.follow-up." + outcome + ".body").formatted(contractNumber, orderNumber),
                "/admin/orders/" + orderId, "ORDER", orderId,
                "contract-credit-review:%s:%s".formatted(reviewId, approved ? "approved" : "refused"));
    }

    /** 15 BR-11: whoever may decide credit hears again of each contract left waiting past the line, once each. */
    private int creditReviewsWaiting(List<ContractCreditReviews.Waiting> waiting, ResourceBundle texts) {
        int told = 0;
        for (ContractCreditReviews.Waiting review : waiting) {
            List<UUID> deciders = decidersOtherThan(review.submittedBy());
            if (deciders.isEmpty()) {
                log.warn("Contract {} waits for accounting and nobody else holds {} - nobody was told",
                        review.contractNumber(), DECIDES_CREDIT);
                continue;
            }
            told += inbox.post(deciders, new InboxMessage(CATEGORY, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.contract-credit-review-waiting.title")
                            .formatted(review.contractNumber()),
                    texts.getString("inbox.sales.follow-up.contract-credit-review-waiting.body")
                            .formatted(review.contractNumber(), review.orderNumber(),
                                    DAY.format(BusinessCalendar.date(review.submittedAt()))),
                    CREDIT_REVIEWS_PAGE, "ORDER", review.orderId().toString(),
                    "contract-credit-review:%s:waiting".formatted(review.reviewId())));
        }
        return told;
    }

    private List<UUID> decidersOtherThan(UUID submittedBy) {
        return identity.notificationRecipientsHolding(DECIDES_CREDIT, null).stream().map(StaffContact::userId)
                .filter(id -> !id.equals(submittedBy)).distinct().toList();
    }

    /**
     * FLOW-1b: accounting decided an order held for credit - whoever looks after the order's customer is told now, in
     * the same transaction as the decision (00 §6, 15 BR-22). Only the outcome: the note stays on the order.
     */
    void creditDecided(UUID orderId, String orderNumber, UUID customerId, boolean approved) {
        ResourceBundle texts = texts();
        String outcome = approved ? "credit-approved" : "credit-refused";
        SalesRecipients.Recipients to = recipients.chooser().of(customerId);
        int told = post(to, texts, approved ? InboxSeverity.INFO : InboxSeverity.WARN,
                texts.getString("inbox.sales.follow-up." + outcome + ".title").formatted(orderNumber),
                texts.getString("inbox.sales.follow-up." + outcome + ".body").formatted(orderNumber),
                "/admin/orders/" + orderId, "ORDER", orderId, "credit-decided:" + orderId);
        if (!to.nobody()) {
            log.info("Order {}: {}, {} Sales account(s) newly told{}", orderNumber, outcome, told,
                    to.needsAssignment() ? " (the customer needs an owner)" : "");
        }
    }

    /** FLOW-1a: whoever may decide credit hears of each order held for it, once per hold. */
    private int creditHolds(List<SalesFollowUps.WaitingOrder> held, ResourceBundle texts) {
        if (held.isEmpty()) {
            return 0;
        }
        List<UUID> deciders = identity.notificationRecipientsHolding(DECIDES_CREDIT, null).stream()
                .map(StaffContact::userId).distinct().toList();
        if (deciders.isEmpty()) {
            log.warn("{} order(s) held for credit and nobody holds {} - nobody was told", held.size(), DECIDES_CREDIT);
            return 0;
        }
        int told = 0;
        for (SalesFollowUps.WaitingOrder order : held) {
            told += inbox.post(deciders, new InboxMessage(CATEGORY, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.credit-hold.title").formatted(order.orderNumber()),
                    texts.getString("inbox.sales.follow-up.credit-hold.body").formatted(order.orderNumber(),
                            DAY.format(BusinessCalendar.date(order.since()))),
                    "/admin/orders/credit-holds", "ORDER", order.orderId().toString(),
                    "credit-hold:%s:%s".formatted(order.orderId(), order.since())));
        }
        return told;
    }

    private int post(SalesRecipients.Recipients to, ResourceBundle texts, InboxSeverity severity, String title,
                     String body, String link, String sourceType, UUID sourceId, String dedupeKey) {
        if (to.nobody()) {
            log.warn("{}: the customer has no active owner and no Sales Manager or admin is active - nobody was told",
                    dedupeKey);
            return 0;
        }
        String text = to.needsAssignment() ? body + "\n" + texts.getString("inbox.sales.needs-assignment") : body;
        return inbox.post(new ArrayList<>(to.userIds()), new InboxMessage(CATEGORY, severity, title, text, link,
                sourceType, sourceId.toString(), dedupeKey));
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
