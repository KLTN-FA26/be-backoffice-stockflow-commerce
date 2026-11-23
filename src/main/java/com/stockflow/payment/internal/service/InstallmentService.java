package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.ContractInstallments;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import com.stockflow.contracts.InstallmentDue;
import com.stockflow.contracts.InstallmentOverdue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed contract's installments (4B-6, kltn-docs 15 §4.1, §4.3, §5.1, §5.2): opened with the signature, due when
 * their milestone comes, overdue - a flag, not a status - past their due day. Receipts pay them ({@link PaymentReceiptService}); a cancellation
 * or a closure of the rest settles them with the order ({@link OrderSettlementService}, 4B-9).
 *
 * <ul>
 *   <li>ON_SIGNING: due from the signature, by the day signed plus its days.</li>
 *   <li>BEFORE_DELIVERY: due when the order is released to be shipped ({@code OrderReleased}); no due day - it blocks
 *       the handover until paid (BR-08), which the order module reads through {@link InstallmentStanding}.</li>
 *   <li>AFTER_DELIVERY: due when the order is delivered in full - the last delivery, or the rest closed after it (15
 *       D23) - by that day plus its days (BR-09): the customer's receivable.</li>
 * </ul>
 *
 * <p>An installment tied to a delivery batch follows that batch (4B-8, 17 §4.5): one before delivery falls due when the
 * batch is released, one after delivery when the batch is delivered in full.</p>
 *
 * <p><b>Locks: the order's row, then the installments in id order</b> ({@link InstallmentOrders#lockForPayment}, then
 * {@link InstallmentBook#lockOfOrder}) in every listener-driven change here (WF-05 BE-41) - the order every money
 * movement takes. Reading the order's facts without its lock let a correction commit between the read and the
 * installments' lock, and the milestone was reached from a delivery that no longer existed.</p>
 */
@Service
@Transactional
public class InstallmentService {

    private static final Logger log = LoggerFactory.getLogger(InstallmentService.class);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** A scan reads at most 50 pages of 200 installments - far above what turns overdue in a few days. */
    static final int SCAN_MAX_PAGES = 50;

    private static final SortWhitelist SORT = SortWhitelist.of("dueDate", "seq", "amount", "status", "createdAt")
            .withDefault("dueDate", Sort.Direction.ASC);

    /** An installment and the receipts that paid it. */
    public record InstallmentDetail(Installment installment, List<InstallmentBook.ReceiptOfInstallment> receipts) {
    }

    /** An order's installments and where they stand (15 §5.2). */
    public record OrderInstallments(InstallmentStanding standing, List<Installment> installments) {
    }

    /**
     * How the customer pays one installment by bank transfer (15 §4.2 step 5): the company's account, what is left to
     * pay, and what to write in the transfer.
     */
    public record TransferInstructions(Installment installment, CompanyBankAccount account, BigDecimal amountDue,
                                       String reference) {
    }

    private final InstallmentBook book;
    private final InstallmentSearch search;
    private final InstallmentOrders orders;
    private final CompanyBankProperties bank;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Autowired
    InstallmentService(InstallmentBook book, InstallmentSearch search, InstallmentOrders orders, CompanyBankProperties bank,
                       Clock clock, ApplicationEventPublisher events) {
        this.book = book;
        this.search = search;
        this.orders = orders;
        this.bank = bank;
        this.clock = clock;
        this.events = events;
    }

    /** Without anyone told that an installment fell due: the unit tests that drive the arithmetic alone. */
    InstallmentService(InstallmentBook book, InstallmentSearch search, InstallmentOrders orders, CompanyBankProperties bank,
                       Clock clock) {
        this(book, search, orders, bank, clock, event -> { });
    }

    /** {@link com.stockflow.payment.api.PaymentService#openInstallments}. */
    public void open(ContractInstallments contract) {
        if (book.opened(contract.contractId())) {
            return;
        }
        LocalDate signedOn = contract.signedOn();
        for (ContractInstallments.Planned planned : contract.installments()) {
            Installment installment = Installment.open(contract.contractId(), planned.contractInstallmentId(),
                    contract.orderId(), contract.customerId(), contract.contractNumber(), contract.orderNumber(),
                    planned.seq(), planned.name(), InstallmentMilestone.valueOf(planned.milestone()),
                    planned.deliveryBatchId(), planned.deliveryBatchSeq(), planned.amount(), contract.currency(),
                    planned.dueDays());
            boolean due = installment.milestone() == InstallmentMilestone.ON_SIGNING
                    && installment.reach(contract.signedAt(), signedOn);
            book.save(installment);
            if (due) {
                fellDue(installment);
            }
        }
        log.info("Contract {} of order {}: {} installment(s) opened", contract.contractNumber(), contract.orderNumber(),
                contract.installments().size());
    }

    /** The order was released to be shipped: what it pays before delivery is due (15 §4.1 step 2). */
    public void released(UUID orderId, Instant at) {
        released(orderId, at, null);
    }

    /**
     * 4B-8 (15 §4.1, 17 §4.5): a delivery batch released makes due the installments before delivery tied to it, and
     * those tied to no batch (due with the first release); null: the order released whole, every one of them.
     */
    public void released(UUID orderId, Instant at, UUID deliveryBatchId) {
        orders.lockForPayment(orderId);
        LocalDate day = LocalDate.ofInstant(at, BUSINESS_ZONE);
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() == InstallmentMilestone.BEFORE_DELIVERY
                    && (deliveryBatchId == null || installment.deliveryBatchId() == null
                            || deliveryBatchId.equals(installment.deliveryBatchId()))
                    && installment.reach(at, day)) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {} is due (BEFORE_DELIVERY{})", installment.seq(),
                        installment.contractNumber(), deliveryBatchId == null ? "" : ", its delivery batch released");
            }
        }
    }

    /** {@link com.stockflow.payment.api.PaymentService#dueBeforeHandover(UUID, UUID)}. */
    @Transactional(readOnly = true)
    public java.math.BigDecimal dueBeforeHandover(UUID orderId, UUID deliveryBatchId) {
        return book.ofOrder(orderId).stream().filter(i -> i.status() != InstallmentStatus.CANCELLED)
                .filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING
                        || i.milestone() == InstallmentMilestone.BEFORE_DELIVERY
                        && (deliveryBatchId == null || i.deliveryBatchId() == null
                                || deliveryBatchId.equals(i.deliveryBatchId())))
                .map(Installment::outstanding).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /**
     * Goods of the order were delivered, or the rest of it closed (15 §4.1 step 2, D23): what it pays after delivery is
     * due from the day its goods were delivered. An installment tied to a delivery batch counts from the day that batch
     * was delivered in full (4B-8, 17 §4.5); one tied to no batch, from the day the whole order was. A delivery of part
     * of a batch, or of a split order, waits for the last one. One already due from another day than the order now has
     * is moved to it ({@link #follow}, WF-05 BE-04): the order's "delivered in full" is the latest of its deliveries and
     * closures, which a delivery recorded late, or a return closed after the last delivery, moves.
     *
     * @param wholeOrder the event delivered the whole order at once (no shipment of its own)
     */
    public void delivered(UUID orderId, Instant at, boolean wholeOrder) {
        orders.lockForPayment(orderId);
        if (wholeOrder) {
            reach(orderId, InstallmentMilestone.AFTER_DELIVERY, at);
            return;
        }
        follow(orderId, false);
    }

    /**
     * A delivery of the order was corrected to a failure (kltn-docs 09 §4.3 step 5, 15 §4.1; E17: the milestone of an
     * installment paid after delivery is worked out again from the corrected outcome; WF-05 GH-3). {@link #delivered}
     * reached a milestone once, so without this the installment of a delivery that never happened stayed due from the
     * wrong day: overdue thirty days later, the customer refused new releases and new contracts held for credit.
     *
     * <p>The one path that takes a milestone back ({@link Installment#withdrawMilestone}): the order no longer has the
     * delivery it counted from - due again only when the goods are delivered again. A milestone the order has from
     * another moment is moved to it, as on every other path ({@link #follow}).</p>
     */
    public void deliveryCorrected(UUID orderId) {
        orders.lockForPayment(orderId);
        follow(orderId, true);
    }

    /**
     * WF-05 decisions Q-2, Q-3, Q-19 (BE-02): the units of a shipment the carrier brought back, or lost, were closed and
     * settled ({@code ShipmentReturnSettled}). Their money was settled in the closing transaction
     * ({@link OrderSettlementService#settleShipmentReturn}); what is left here is when the rest falls due. The order may
     * now count as delivered in full - nothing outstanding, the closure's day part of its "delivered in full" (Q-3: the
     * later of the last delivery and the goods back) - so the installments after delivery are reached or moved from what
     * the order now says.
     *
     * <p>An order that delivered nothing and expects nothing more (a whole carrier return, or every shipment lost)
     * has no "delivered in full" at all, and what the installments after delivery still owe - the plate fee Q-19 keeps,
     * a share of the goods the customer had not paid - would never fall due (audit M-1, scenario C). They count from the
     * goods back instead, plus the contract's days: Q-19 sets no second rule, and Q-3's day is the goods back. That day
     * is the order's fact ({@code InstallmentOrders.endedWithNothingDeliveredAt}), read by {@link #follow} on every path
     * - this one and a closure of the rest ({@link #delivered}) - not carried by this event alone (audit r2 money M-1).</p>
     *
     * <p>Worked out from the order's facts, never from the event alone: heard twice, or after a later event, it changes
     * nothing more.</p>
     */
    public void shipmentReturnSettled(UUID orderId) {
        orders.lockForPayment(orderId);
        follow(orderId, false);
    }

    /**
     * The one rule every path shares (WF-05 BE-04): each installment paid after delivery is held against the days the
     * order has <b>now</b> - tied to a batch, the moment that batch was delivered in full; tied to none, or its batch not
     * delivered in full, the moment the whole order was - or, for an order that delivered nothing and expects nothing
     * more, the moment it ended (Q-3 "the goods back", Q-19; {@code InstallmentOrders.endedWithNothingDeliveredAt}).
     * One rule for every path, so a whole return whose rest was closed last falls due exactly as one whose last
     * shipment's receipt came last (audit r2 money M-1).
     * <ul>
     *   <li>not reached, and the order has its day: reached, due by that day plus its days;</li>
     *   <li>reached from another day, still owing: moved to the order's day ({@link Installment#redate}) - the customer
     *       is never overdue from a delivery recorded early, nor left owing from a stale day;</li>
     *   <li>reached, and the order no longer has a day: taken back on a correction only ({@code correction}); on any
     *       other path the order not knowing its day is no news that the delivery did not happen.</li>
     * </ul>
     * Every installment reached or moved is announced ({@code InstallmentDue}) with the due day it has now. The chat
     * draws one transfer card per installment and does not redraw it on a second announcement, so a card posted before
     * the move shows the old day until the card is next refreshed (a receipt, a void) - a gap in the chat, not in the
     * money: the overdue scan, the credit position and the aged receivables all read the installment itself.
     */
    private void follow(UUID orderId, boolean correction) {
        java.util.Map<UUID, Instant> batches = orders.batchesDeliveredAt(orderId);
        Instant whole = orders.deliveredInFullAt(orderId)
                .or(() -> orders.endedWithNothingDeliveredAt(orderId)).orElse(null);
        if (!correction && batches.isEmpty() && whole == null) {
            return;
        }
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() != InstallmentMilestone.AFTER_DELIVERY) {
                continue;
            }
            Instant from = installment.deliveryBatchId() == null ? whole
                    : batches.getOrDefault(installment.deliveryBatchId(), whole);
            if (!installment.reached()) {
                if (from != null && installment.reach(from, LocalDate.ofInstant(from, BUSINESS_ZONE))) {
                    book.save(installment);
                    fellDue(installment);
                    log.info("Installment {} of contract {} is due (AFTER_DELIVERY) by {}", installment.seq(),
                            installment.contractNumber(), installment.dueDate());
                }
                continue;
            }
            if (from == null) {
                if (correction && installment.withdrawMilestone()) {
                    book.save(installment);
                    log.info("Installment {} of contract {}: a delivery was corrected to a failure, not due until the "
                            + "goods are delivered again", installment.seq(), installment.contractNumber());
                }
                continue;
            }
            LocalDate before = installment.dueDate();
            if (installment.redate(from, LocalDate.ofInstant(from, BUSINESS_ZONE))) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {}: due by {} instead of {} - the order counts as delivered in "
                        + "full from another day", installment.seq(), installment.contractNumber(),
                        installment.dueDate(), before);
            }
        }
    }

    private void reach(UUID orderId, InstallmentMilestone milestone, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, BUSINESS_ZONE);
        for (Installment installment : book.lockOfOrder(orderId)) {
            if (installment.milestone() == milestone && installment.reach(at, day)) {
                book.save(installment);
                fellDue(installment);
                log.info("Installment {} of contract {} is due ({}){}", installment.seq(),
                        installment.contractNumber(), milestone,
                        installment.dueDate() == null ? "" : " by " + installment.dueDate());
            }
        }
    }

    /**
     * Tells the modules that follow installments - the chat's transfer card (kltn-docs 16 §4.4 step 9, BR-06) - that
     * one fell due. Published in the transaction that reached its milestone, delivered after commit; the amount it still
     * owes is read when the card is drawn.
     */
    private void fellDue(Installment installment) {
        events.publishEvent(new InstallmentDue(installment.id(), installment.contractId(),
                installment.contractNumber(), installment.orderId(), installment.customerId(), installment.seq(),
                installment.dueDate(), installment.reachedAt()));
    }

    /**
     * The daily overdue scan (kltn-docs 15 §4.3 step 11, WF4-OVERDUE-TRIGGER): publishes {@link InstallmentOverdue} for
     * every installment overdue today - past its due day, still owing - that was not announced for its current due date,
     * and writes that date down ({@link InstallmentBook#markOverdueAnnounced}), so Sales hear of it once per due date and
     * may hold the customer's orders by hand.
     *
     * <p>WF-05 BE-49: the scan used to read back three days of due dates and keep no record. An installment reached with
     * a due date already further back - a delivery recorded late, a milestone moved - was overdue at once and never
     * announced; one announced and then given a new due date was never announced again. Overdue is still the one
     * definition the application repeats ({@link Installment#overdue}, {@code payment.installment_is_overdue}); the
     * record is only what was told. The mark and the event commit together, and the listener's key names the due date
     * too ({@code installment-overdue:<id>:<due date>}), so a replay tells nobody twice.</p>
     *
     * <p>Each round reads the first page of what is still to announce - what this run marked has left it - until a page
     * brings nothing new. One a receipt holds right now is skipped, not waited for, and comes again the next night.</p>
     *
     * @return how many installments were announced
     */
    public int announceNewlyOverdue() {
        LocalDate today = today();
        InstallmentSearch.InstallmentFilter filter = new InstallmentSearch.InstallmentFilter(null, null, null, null,
                null, null, true, today, null, null, null, true);
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        int announced = 0;
        for (int round = 0; round < SCAN_MAX_PAGES; round++) {
            var found = search.installments(filter, Pages.of(0, Pages.MAX_PAGE_SIZE, Sort.by("dueDate", "id")));
            boolean anyNew = false;
            for (Installment installment : found.getContent()) {
                if (!seen.add(installment.id())) {
                    continue;
                }
                anyNew = true;
                if (!book.markOverdueAnnounced(installment.id(), installment.dueDate())) {
                    continue;
                }
                events.publishEvent(new InstallmentOverdue(installment.id(), installment.contractId(),
                        installment.contractNumber(), installment.orderId(), installment.orderNumber(),
                        installment.customerId(), installment.seq(), installment.dueDate(),
                        installment.outstanding(), installment.currency()));
                announced++;
            }
            if (!anyNew || !found.hasNext()) {
                break;
            }
        }
        log.info("Overdue scan: {} installment(s) announced overdue", announced);
        return announced;
    }

    /**
     * The business day overdue is read on (15 §4.1 step 4): an installment is overdue from the day after its due day, in
     * the business calendar - worked out when asked, by no nightly job (WF-05 fix plan, section 8).
     */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    /** {@link com.stockflow.payment.api.PaymentService#installmentStanding}. */
    @Transactional(readOnly = true)
    public InstallmentStanding standing(UUID orderId) {
        return standingOf(orderId, book.ofOrder(orderId));
    }

    /** {@link #standing} of each order, from one read of all their installments; every id asked is a key. */
    public java.util.Map<UUID, InstallmentStanding> standings(java.util.Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<UUID> ids = orderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<UUID, List<Installment>> byOrder = book.ofOrders(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(Installment::orderId));
        java.util.Map<UUID, InstallmentStanding> standings = new java.util.HashMap<>();
        ids.forEach(id -> standings.put(id, standingOf(id, byOrder.getOrDefault(id, List.of()))));
        return standings;
    }

    static InstallmentStanding standingOf(UUID orderId, List<Installment> installments) {
        if (installments.isEmpty()) {
            return InstallmentStanding.none(orderId);
        }
        List<Installment> live = installments.stream().filter(i -> i.status() != InstallmentStatus.CANCELLED)
                .toList();
        boolean onSigningPaid = live.stream().filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING)
                .allMatch(i -> i.status() == InstallmentStatus.PAID);
        BigDecimal beforeDelivery = live.stream().filter(i -> i.milestone() == InstallmentMilestone.BEFORE_DELIVERY)
                .map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal onSigning = live.stream().filter(i -> i.milestone() == InstallmentMilestone.ON_SIGNING)
                .map(Installment::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        // What closures took off is no longer owed (4B-9, 15 §4.6 step 19).
        BigDecimal total = live.stream().map(Installment::owedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Every installment cancelled with its order: nothing is owed any more, but what the customer paid was paid -
        // the order says so beside its refund, not "0 paid" (15 §5.2 skips cancelled installments for the status only).
        // While something stays owed (a paid installment, a cancellation fee's shortfall), paid stays the live
        // installments' own: the money collected on the cancelled ones went to the fee or to the refund (4B-9), and
        // counting it here again would understate what is still owed (total - paid).
        BigDecimal paid = (live.isEmpty() ? installments : live).stream().map(Installment::paidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        InstallmentStanding.ContractPaymentStatus status = live.isEmpty() ? null
                : live.stream().allMatch(i -> i.status() == InstallmentStatus.PAID)
                ? InstallmentStanding.ContractPaymentStatus.PAID
                : paid.signum() > 0 ? InstallmentStanding.ContractPaymentStatus.PARTIALLY_PAID
                : InstallmentStanding.ContractPaymentStatus.UNPAID;
        return new InstallmentStanding(orderId, true, onSigningPaid, beforeDelivery, beforeDelivery.add(onSigning),
                total, paid, status);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * Every filter null means "any"; {@code dueBefore} keeps installments due before that day; {@code reached} true keeps
     * those whose milestone came (what is due), false those still waiting for it; {@code overdue} true keeps those
     * overdue today, false those that are not.
     *
     * @throws BusinessException {@code INSTALLMENT_OVERDUE_IS_A_FLAG} (400) for {@code status} OVERDUE: no installment is
     *         in it any more ({@link InstallmentStatus}), so the filter would answer an empty list - which a client
     *         written against the old status reads as "nothing overdue". It is told to send {@code overdue=true}.
     */
    @Transactional(readOnly = true)
    public PageResponse<Installment> installments(UUID customerId, UUID orderId, InstallmentStatus status,
                                                  InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                                  Boolean overdue, int page, int size, String sort) {
        return installments(customerId, null, orderId, status, milestone, dueBefore, reached, overdue, null, page,
                size, sort);
    }

    /**
     * {@link #installments(UUID, UUID, InstallmentStatus, InstallmentMilestone, LocalDate, Boolean, Boolean, int, int,
     * String)}, also kept to the customers {@code customerIds} (null: any; empty: none - a customer search that matched
     * nobody) and, {@code owing} true, to what is due and still owed: the milestone came and it is DUE or PARTIALLY_PAID,
     * fee items and overdue ones included ({@link InstallmentSearch.InstallmentFilter}).
     */
    @Transactional(readOnly = true)
    public PageResponse<Installment> installments(UUID customerId, java.util.Collection<UUID> customerIds,
                                                  UUID orderId, InstallmentStatus status,
                                                  InstallmentMilestone milestone, LocalDate dueBefore, Boolean reached,
                                                  Boolean overdue, Boolean owing, int page, int size, String sort) {
        if (status == InstallmentStatus.OVERDUE) {
            throw new BusinessException(ErrorCode.INSTALLMENT_OVERDUE_IS_A_FLAG,
                    "OVERDUE is not an installment status any more; filter with overdue=true")
                    .localized("refusal.payment.overdueIsAFlag");
        }
        return Pages.toResponse(search.installments(new InstallmentSearch.InstallmentFilter(customerId, orderId, status,
                milestone, dueBefore, reached, overdue, overdue == null ? null : today(), null, customerIds, owing),
                Pages.of(page, size, SORT.parse(sort))));
    }

    /**
     * One installment and its receipts; {@code ownCustomerId} not null: only that customer's (anyone else's is not
     * found, as if it did not exist).
     */
    @Transactional(readOnly = true)
    public InstallmentDetail detail(UUID installmentId, UUID ownCustomerId) {
        Installment installment = find(installmentId, ownCustomerId);
        return new InstallmentDetail(installment, book.receiptsOf(installmentId));
    }

    /** The order's installments and their standing; {@code ownCustomerId} as for {@link #detail}. */
    @Transactional(readOnly = true)
    public OrderInstallments ofOrder(UUID orderId, UUID ownCustomerId) {
        List<Installment> installments = book.ofOrder(orderId);
        if (installments.isEmpty() || ownCustomerId != null
                && !ownCustomerId.equals(installments.getFirst().customerId())) {
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND,
                    "Order %s has no installments".formatted(orderId)).localized("refusal.payment.orderNoInstallments");
        }
        return new OrderInstallments(standingOf(orderId, installments), installments);
    }

    /**
     * How to pay the installment (15 §4.2 step 5): what it still owes, to the company's account, writing its reference.
     *
     * @throws BusinessException {@code INSTALLMENT_NOT_FOUND}; {@code INSTALLMENT_NOT_PAYABLE} when paid or cancelled;
     *         {@code TRANSFER_INSTRUCTIONS_UNAVAILABLE} while the company's account is not configured
     */
    @Transactional(readOnly = true)
    public TransferInstructions transferInstructions(UUID installmentId, UUID ownCustomerId) {
        Installment installment = find(installmentId, ownCustomerId);
        if (!installment.status().takesMoney()) {
            // The status goes in as the enum: Messages.refusal reads it as its label (label.InstallmentStatus.PAID).
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, "Installment %d of contract %s is %s"
                    .formatted(installment.seq(), installment.contractNumber(), installment.status()))
                    .localized("refusal.payment.notPayable", installment.seq(), installment.contractNumber(),
                            installment.status());
        }
        if (!bank.configured()) {
            throw new BusinessException(ErrorCode.TRANSFER_INSTRUCTIONS_UNAVAILABLE,
                    "The company's bank account is not configured");
        }
        // The BIN goes along: without it the chat's transfer card draws no VietQR code.
        CompanyBankAccount account = new CompanyBankAccount(bank.bankName().strip(), bank.accountNumber().strip(),
                bank.accountName().strip(), bank.bin());
        return new TransferInstructions(installment, account, installment.outstanding(),
                installment.transferReference());
    }

    private Installment find(UUID installmentId, UUID ownCustomerId) {
        Optional<Installment> found = book.find(installmentId)
                .filter(i -> ownCustomerId == null || ownCustomerId.equals(i.customerId()));
        return found.orElseThrow(() -> new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND,
                "No installment " + installmentId).localized("refusal.payment.installmentNotFound"));
    }
}
