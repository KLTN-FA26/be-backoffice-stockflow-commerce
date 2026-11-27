package com.stockflow.payment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.payment.internal.domain.BankTransaction;
import com.stockflow.payment.internal.domain.BankTransaction.ReviewReason;
import com.stockflow.payment.internal.domain.BankTransactionLog;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import com.stockflow.payment.internal.domain.BankTransfer;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.TransferReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Transfers the bank feed reports (credit plan 2026-11-26 part C; kltn-docs 15 BR-02, §4.2): SePay reads the company's
 * account, as the accounting software does, so nobody types a transfer twice. Shaped like the carriers' webhook
 * ({@code CarrierEventService}): a call is kept before anything is done with it, then matched and applied, in short
 * transactions none of which is open while another runs.
 *
 * <ol>
 *   <li><b>Kept</b> ({@code RECEIVED}): every incoming transfer, once - by SePay's id, and by the bank's reference
 *       ({@link BankTransfer#dedupeKey}). Money going out is no customer's payment and is not kept.</li>
 *   <li><b>Matched</b>: the transfer's content names an installment the way the transfer card writes it
 *       ({@link TransferReference}); SePay's payment code is read when the content names none.</li>
 *   <li><b>Applied</b> ({@code APPLIED}) when it names exactly one installment, which is payable and owes at least the
 *       amount: a receipt is recorded through {@link PaymentReceiptService#record} - the same rules (BR-03..BR-05), the
 *       same {@code PaymentReceiptRecorded}, so the order confirms itself, and the handover gate opens, exactly as for
 *       one the Accountant records. Its reference is the bank's (BR-03 then refuses the same money twice) and its
 *       recorder the bank feed's system user ({@link BankFeedUser}).</li>
 *   <li>Anything else waits for the Accountant ({@code NEEDS_REVIEW}, with a {@link ReviewReason}), who assigns it to
 *       installments ({@link #assign}, which records the receipt) or ignores it with a reason ({@link #ignore}).</li>
 * </ol>
 *
 * <p>A failure that is not the transfer's fault (a deadlock, a lost connection) leaves it RECEIVED: SePay sends the call
 * again when told to (503), and the nightly {@link #catchUp} handles what is still stranded. Locks: the transfer's row
 * first, then what {@link PaymentReceiptService#record} takes (orders, receipt, installments) - every bank-feed write
 * takes them in that order.</p>
 */
@Service
public class BankTransactionService {

    /** A RECEIVED transfer younger than this is still being handled, not stranded. */
    static final Duration STILL_HANDLED = Duration.ofMinutes(5);
    /** At most this many pages a night: 100 transfers a page, a few days back. */
    static final int MAX_PAGES = 50;
    /** SePay's API v2 takes three calls a second. */
    static final Duration PAGE_PAUSE = Duration.ofMillis(400);

    private static final Logger log = LoggerFactory.getLogger(BankTransactionService.class);

    /** What the webhook answers. */
    public enum WebhookAnswer {
        /** Kept (or a repeat of one kept, or money going out): HTTP 200 {@code {"success": true}}. */
        TAKEN,
        /** The credential does not hold: 401. */
        UNAUTHENTICATED,
        /** The feed is off, or no credential is configured: 503. */
        UNAVAILABLE,
        /** Authenticated but not a transfer this system can read: 400. */
        UNREADABLE,
        /** Kept, not handled because of a passing failure: 503, for SePay to send it again. */
        RETRY
    }

    /** What one catch-up did. */
    public record CatchUp(int read, int kept, int retried, boolean listRead) {
    }

    /** A transfer and the receipt reference it carries or would carry, for display. */
    public record View(BankTransaction transaction, String receiptReference) {
    }

    /** What the Accountant assigns a transfer to. */
    public record AssignCommand(UUID customerId, List<PaymentReceipt.Payment> lines, String note) {
    }

    private record Decision(ReviewReason reason, String note, Installment installment) {

        static Decision review(ReviewReason reason, String note, Installment installment) {
            return new Decision(reason, note, installment);
        }
    }

    private enum Outcome { NEW, REPEAT, RETRY }

    private final BankTransactionLog transactions;
    private final InstallmentBook book;
    private final PaymentReceiptService receipts;
    private final SepayTransactionsClient api;
    private final BankFeedProperties feed;
    private final ObjectMapper json;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    BankTransactionService(BankTransactionLog transactions, InstallmentBook book, PaymentReceiptService receipts,
                           SepayTransactionsClient api, BankFeedProperties feed, ObjectMapper json,
                           TransactionOperations tx, Clock clock) {
        this.transactions = transactions;
        this.book = book;
        this.receipts = receipts;
        this.api = api;
        this.feed = feed;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ receiving

    /**
     * A call of SePay's webhook: authenticated on the raw body, read, kept, then matched and applied.
     *
     * @param header the call's headers by name
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WebhookAnswer receiveWebhook(UnaryOperator<String> header, byte[] body) {
        if (!feed.enabled() || !(feed.webhook().hmac() || feed.webhook().key())) {
            log.warn("Bank-feed webhook refused: the feed is {}", feed.enabled() ? "on without a credential" : "off");
            return WebhookAnswer.UNAVAILABLE;
        }
        if (!SepayWebhook.authentic(feed.webhook(), header, body, clock.instant())) {
            log.warn("Bank-feed webhook refused: the credential does not hold ({} bytes)",
                    body == null ? 0 : body.length);
            return WebhookAnswer.UNAUTHENTICATED;
        }
        SepayWebhook.Reading reading = SepayWebhook.read(body, json);
        return switch (reading) {
            case SepayWebhook.Unreadable unreadable -> {
                log.warn("Bank-feed webhook unreadable: {}", unreadable.why());
                yield WebhookAnswer.UNREADABLE;
            }
            case SepayWebhook.Outgoing outgoing -> {
                log.debug("Bank-feed webhook {}: money going out, not kept", outgoing.providerTransactionId());
                yield WebhookAnswer.TAKEN;
            }
            case SepayWebhook.Incoming incoming ->
                    handle(incoming.transfer(), BankTransaction.Source.WEBHOOK) == Outcome.RETRY
                            ? WebhookAnswer.RETRY : WebhookAnswer.TAKEN;
        };
    }

    /**
     * The nightly catch-up: the incoming transfers SePay's transaction API lists for the last few days, kept and applied
     * unless they were already; then every transfer left RECEIVED by a failure, handled again. SePay not answering is
     * logged, never thrown: the stranded ones are still handled.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CatchUp catchUp() {
        if (!feed.enabled()) {
            return new CatchUp(0, 0, 0, false);
        }
        int read = 0;
        int kept = 0;
        boolean listRead = false;
        if (api.configured()) {
            Instant to = clock.instant();
            Instant from = to.minus(feed.api().lookback());
            try {
                for (int page = 1; page <= MAX_PAGES; page++) {
                    SepayTransactionsClient.Page found = api.incoming(from, to, page);
                    for (BankTransfer transfer : found.transfers()) {
                        read++;
                        if (handle(transfer, BankTransaction.Source.CATCH_UP) == Outcome.NEW) {
                            kept++;
                        }
                    }
                    if (!found.hasMore()) {
                        listRead = true;
                        break;
                    }
                    pause();
                }
            } catch (SepayTransactionsClient.Unavailable unavailable) {
                log.warn("Bank-feed catch-up: {}", unavailable.getMessage());
            }
        }
        List<UUID> stranded = Objects.requireNonNull(tx.execute(s ->
                transactions.stranded(clock.instant().minus(STILL_HANDLED))));
        stranded.forEach(this::apply);
        log.info("Bank-feed catch-up: {} transfer(s) listed, {} new, {} stranded handled again{}", read, kept,
                stranded.size(), listRead || !api.configured() ? "" : " (the list was not read to its end)");
        return new CatchUp(read, kept, stranded.size(), listRead);
    }

    /** Keeps the transfer unless it was kept already, then applies it - or a repeat still RECEIVED - now. */
    private Outcome handle(BankTransfer transfer, BankTransaction.Source source) {
        BankTransaction fresh = BankTransaction.received(BankTransaction.Provider.SEPAY, source, transfer,
                clock.instant());
        boolean kept = Boolean.TRUE.equals(tx.execute(s -> transactions.keep(fresh)));
        UUID id = fresh.id();
        if (!kept) {
            BankTransaction same = tx.execute(s -> transactions.findSame(BankTransaction.Provider.SEPAY,
                    transfer.providerTransactionId(), transfer.dedupeKey()).orElse(null));
            if (same == null || same.status() != BankTransactionStatus.RECEIVED) {
                return Outcome.REPEAT;
            }
            id = same.id();
        }
        boolean handled = apply(id);
        return !handled ? Outcome.RETRY : kept ? Outcome.NEW : Outcome.REPEAT;
    }

    /**
     * Matches a RECEIVED transfer and decides it: a receipt, or the Accountant's queue. False when a passing failure
     * left it RECEIVED.
     */
    boolean apply(UUID id) {
        Decision decision;
        try {
            decision = tx.execute(s -> transactions.find(id).filter(t -> t.status() == BankTransactionStatus.RECEIVED)
                    .map(this::decide).orElse(null));
            if (decision == null) {
                return true;
            }
            if (decision.reason() != null) {
                tx.executeWithoutResult(s -> review(id, decision.reason(), decision.note(), decision.installment()));
                return true;
            }
            tx.executeWithoutResult(s -> record(id, decision.installment()));
            return true;
        } catch (BusinessException refused) {
            // The receipt's own rules said no (a cancelled order, the reference recorded already, ...): rolled back,
            // and the transfer goes to the Accountant with what was said.
            Installment hint = tx.execute(s -> transactions.find(id).map(this::decide).map(Decision::installment).orElse(null));
            tx.executeWithoutResult(s -> review(id, reasonOf(refused), refused.getMessage(), hint));
            return true;
        } catch (RuntimeException failure) {
            log.error("Bank transaction {} could not be applied now; left RECEIVED", id, failure);
            return false;
        }
    }

    /** Which installment the transfer names, and whether it may be paid with the whole amount; read, not locked. */
    private Decision decide(BankTransaction t) {
        BankTransfer transfer = t.transfer();
        String account = digits(feed.accountNumber());
        if (!account.isEmpty() && transfer.accountNumber() != null && !account.equals(digits(transfer.accountNumber()))) {
            return Decision.review(ReviewReason.OTHER_ACCOUNT, ("Paid into account %s, not the account customers pay "
                    + "into").formatted(transfer.accountNumber()), null);
        }
        List<TransferReference> named = TransferReference.read(transfer.content());
        if (named.isEmpty()) {
            named = TransferReference.read(transfer.paymentCode());
        }
        if (named.isEmpty()) {
            return Decision.review(ReviewReason.NO_INSTALLMENT_NAMED,
                    "The transfer's content names no contract installment", null);
        }
        if (named.size() > 1) {
            return Decision.review(ReviewReason.SEVERAL_INSTALLMENTS,
                    "The transfer's content names %d installments: split it by hand".formatted(named.size()), null);
        }
        TransferReference reference = named.getFirst();
        List<UUID> ids = transactions.installmentsNamed(reference.contractNumbers(), reference.seq());
        if (ids.isEmpty()) {
            return Decision.review(ReviewReason.INSTALLMENT_NOT_FOUND, "No installment %d of contract %s"
                    .formatted(reference.seq(), String.join(" or ", reference.contractNumbers())), null);
        }
        if (ids.size() > 1) {
            return Decision.review(ReviewReason.SEVERAL_INSTALLMENTS, "The content may mean contract %s"
                    .formatted(String.join(" or ", reference.contractNumbers())), null);
        }
        Installment installment = book.find(ids.getFirst()).orElse(null);
        if (installment == null) {
            return Decision.review(ReviewReason.INSTALLMENT_NOT_FOUND, "The installment named is gone", null);
        }
        if (installment.status() == InstallmentStatus.PAID || installment.status() == InstallmentStatus.CANCELLED) {
            return Decision.review(ReviewReason.NOT_PAYABLE, "Installment %d of contract %s is %s".formatted(
                    installment.seq(), installment.contractNumber(), installment.status()), installment);
        }
        if (t.amount().compareTo(installment.outstanding()) > 0) {
            return Decision.review(ReviewReason.OVERPAYMENT, "The transfer brought %s; installment %d of contract %s "
                    .formatted(t.amount().toPlainString(), installment.seq(), installment.contractNumber())
                    + "still owes %s".formatted(installment.outstanding().toPlainString()), installment);
        }
        return new Decision(null, null, installment);
    }

    /** The receipt, recorded by the bank feed, under the transfer's lock: still RECEIVED, or nothing is done. */
    private void record(UUID id, Installment installment) {
        BankTransaction t = transactions.lock(id).orElseThrow();
        if (t.status() != BankTransactionStatus.RECEIVED) {
            return;
        }
        PaymentReceiptService.ReceiptDetail detail = receipts.record(new PaymentReceiptService.RecordReceiptCommand(
                installment.customerId(), t.receiptReference(), receivedOn(t), note(t),
                List.of(new PaymentReceipt.Payment(installment.id(), t.amount())), BankFeedUser.ID));
        t.applied(detail.receipt().id(), null, clock.instant());
        transactions.save(t);
        log.info("Bank transaction {} applied: receipt {} for installment {} of contract {}", id,
                detail.receipt().reference(), installment.seq(), installment.contractNumber());
    }

    private void review(UUID id, ReviewReason reason, String note, Installment hint) {
        BankTransaction t = transactions.lock(id).orElseThrow();
        if (t.status() != BankTransactionStatus.RECEIVED) {
            return;
        }
        t.needsReview(reason, note, hint == null ? null : hint.id(), hint == null ? null : hint.customerId(),
                clock.instant());
        transactions.save(t);
        log.info("Bank transaction {} needs review: {} ({})", id, reason, t.reviewNote());
    }

    private static ReviewReason reasonOf(BusinessException refused) {
        return switch (refused.errorCode()) {
            case INSTALLMENT_NOT_PAYABLE -> ReviewReason.NOT_PAYABLE;
            case PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING -> ReviewReason.OVERPAYMENT;
            case INSTALLMENT_NOT_FOUND -> ReviewReason.INSTALLMENT_NOT_FOUND;
            default -> ReviewReason.REFUSED;
        };
    }

    // ------------------------------------------------------------------ the Accountant's queue

    /**
     * The Accountant assigns a transfer waiting for review to installments of one customer: the receipt is recorded
     * under the transfer's reference, by the Accountant, with every rule of {@link PaymentReceiptService#record}; the
     * lines pay no more than the transfer brought (what is left over is settled in the accounting software, BR-06).
     *
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_FOUND}; {@code BANK_TRANSACTION_NOT_OPEN};
     *         {@code BANK_TRANSACTION_OVER_ASSIGNED}; and whatever the receipt refuses
     */
    @Transactional
    public View assign(UUID id, AssignCommand command, CurrentUser actor) {
        BankTransaction t = transactions.lock(id).orElseThrow(() -> notFound(id));
        t.requireReviewable();
        BigDecimal total = command.lines() == null ? BigDecimal.ZERO : command.lines().stream()
                .filter(line -> line != null && line.amount() != null).map(PaymentReceipt.Payment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(t.amount()) > 0) {
            throw new BusinessException(ErrorCode.BANK_TRANSACTION_OVER_ASSIGNED, ("Assigned %s in all, more than the "
                    + "%s the transfer brought").formatted(total.toPlainString(), t.amount().toPlainString()))
                    .localized("refusal.payment.bankTransactionOverAssigned", total.toPlainString(),
                            t.amount().toPlainString());
        }
        String note = command.note() == null || command.note().isBlank() ? note(t) : command.note();
        PaymentReceiptService.ReceiptDetail detail = receipts.record(new PaymentReceiptService.RecordReceiptCommand(
                command.customerId(), t.receiptReference(), receivedOn(t), note, command.lines(), actor.userId()));
        t.applied(detail.receipt().id(), actor.userId(), clock.instant());
        BankTransaction saved = transactions.save(t);
        log.info("Bank transaction {} assigned by {}: receipt {}", id, actor.userId(), detail.receipt().reference());
        return view(saved);
    }

    /**
     * The Accountant decides a transfer waiting for review is no installment's payment here, and says why.
     *
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_FOUND}; {@code BANK_TRANSACTION_NOT_OPEN};
     *         {@code VALIDATION_FAILED} without a reason
     */
    @Transactional
    public View ignore(UUID id, String reason, CurrentUser actor) {
        BankTransaction t = transactions.lock(id).orElseThrow(() -> notFound(id));
        t.ignore(reason, actor.userId(), clock.instant());
        BankTransaction saved = transactions.save(t);
        log.info("Bank transaction {} ignored by {}", id, actor.userId());
        return view(saved);
    }

    /**
     * A receipt was voided (BR-04): the transfer it was recorded from, if any, goes back to the Accountant's queue -
     * the money came in, and is assigned again or ignored.
     */
    @Transactional
    public void receiptVoided(UUID receiptId, String receiptReference) {
        transactions.lockByReceipt(receiptId).ifPresent(t -> {
            t.receiptVoided(receiptReference, clock.instant());
            transactions.save(t);
            log.info("Bank transaction {} back to review: receipt {} was voided", t.id(), receiptReference);
        });
    }

    @Transactional(readOnly = true)
    public View get(UUID id) {
        return view(transactions.find(id).orElseThrow(() -> notFound(id)));
    }

    /** What SePay sent for the transfer, as kept. */
    @Transactional(readOnly = true)
    public String raw(UUID id) {
        return transactions.raw(id).orElseThrow(() -> notFound(id));
    }

    /** Transfers in {@code statuses} (every one when empty), newest first; {@code to} exclusive. */
    @Transactional(readOnly = true)
    public BankTransactionLog.Page list(Collection<BankTransactionStatus> statuses, Instant from, Instant to,
                                        int page, int size) {
        return transactions.search(statuses == null ? Set.of() : new LinkedHashSet<>(statuses), from, to, page, size);
    }

    View view(BankTransaction t) {
        return new View(t, t.receiptReference());
    }

    // ------------------------------------------------------------------ helpers

    /** The day the money came in, in Vietnam - never after today, which a receipt may not be (15 §3). */
    private LocalDate receivedOn(BankTransaction t) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), InstallmentService.BUSINESS_ZONE);
        LocalDate booked = LocalDate.ofInstant(t.transfer().transactionAt(), InstallmentService.BUSINESS_ZONE);
        return booked.isAfter(today) ? today : booked;
    }

    private static String note(BankTransaction t) {
        String content = Optional.ofNullable(t.transfer().content()).orElse("");
        String note = "SePay %s: %s".formatted(t.transfer().providerTransactionId(), content).strip();
        return note.length() > PaymentReceipt.TEXT_MAX ? note.substring(0, PaymentReceipt.TEXT_MAX) : note;
    }

    private static String digits(String account) {
        return account == null ? "" : account.replaceAll("\\s", "");
    }

    private static void pause() {
        try {
            Thread.sleep(PAGE_PAUSE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SepayTransactionsClient.Unavailable("Interrupted between pages");
        }
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.BANK_TRANSACTION_NOT_FOUND, "No bank transaction " + id)
                .localized("refusal.payment.bankTransactionNotFound", id);
    }
}
