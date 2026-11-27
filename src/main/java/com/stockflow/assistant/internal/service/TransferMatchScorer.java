package com.stockflow.assistant.internal.service;

import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.api.PayableInstallment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which installments a bank transfer waiting for review most likely pays (2026-11-27 work features 2.1): every payable
 * installment scored 0-100 from signals anyone can check against the transfer on the screen, at most three above a
 * floor, best first, each with the signals that scored it. Pure: what the installments owe and whether they are overdue
 * is the payment module's ({@link PayableInstallment}); this only weighs.
 *
 * <p><b>The weights</b>, added and capped at 100:</p>
 * <ul>
 *   <li>{@code CONTRACT_NUMBER_IN_CONTENT} 60 when the content names this very installment the way the bank feed reads
 *       it ({@link BankTransferReview.Candidate#namedInContent}); 45 when it names its contract whole but not which
 *       installment;</li>
 *   <li>{@code ORDER_NUMBER_IN_CONTENT} 40, when the contract is not named: the order's number, mangled as banks mangle
 *       it;</li>
 *   <li>{@code INSTALLMENT_IN_CONTENT} 15: beside an order or contract number (never alone: "đợt 2" names nobody's),
 *       {@code DOTn} or {@code FEEn} is this installment's place;</li>
 *   <li>{@code TAX_CODE_IN_CONTENT} 35 and {@code CUSTOMER_NAME_IN_CONTENT} 25: the payer is this customer;</li>
 *   <li>{@code AMOUNT_EXACT} 30: the transfer brought exactly what it still owes; {@code AMOUNT_WITHIN_DUE} 10: less,
 *       a part payment it can take; {@code AMOUNT_ABOVE_DUE} minus 10: more than it owes, which a receipt refuses
 *       (15 BR-05) - the feed leaves exactly such transfers for review (OVERPAYMENT), so a suggestion naming the right
 *       installment must still say the amount cannot be recorded against it as it is;</li>
 *   <li>{@code OVERDUE} or {@code DUE_SOON} (due within {@link #DUE_SOON_DAYS} days) 5: what customers pay first.</li>
 * </ul>
 *
 * <p><b>The floor ({@value #FLOOR})</b> keeps a guess off the panel: the exact amount alone just reaches it - a fair
 * hint the Accountant checks against the customer - and so does the payer's name beside an installment of theirs that
 * is due; a name, a part payment or a due day alone do not. An empty list says "no lead", never "none of these".</p>
 *
 * <p><b>A tie at the top is said</b> ({@link #ambiguous}): several customers' installments owing the same amount score
 * the same on the amount alone, and the panel's "best first" would otherwise read as a preference the figures do not
 * hold.</p>
 */
final class TransferMatchScorer {

    static final int FLOOR = 30;
    static final int MAX_SUGGESTIONS = 3;
    static final int DUE_SOON_DAYS = 7;

    /** Why an installment was suggested, as the panel names it. */
    enum Reason {
        CONTRACT_NUMBER_IN_CONTENT,
        ORDER_NUMBER_IN_CONTENT,
        INSTALLMENT_IN_CONTENT,
        TAX_CODE_IN_CONTENT,
        CUSTOMER_NAME_IN_CONTENT,
        AMOUNT_EXACT,
        AMOUNT_WITHIN_DUE,
        AMOUNT_ABOVE_DUE,
        OVERDUE,
        DUE_SOON
    }

    /** The customer of an installment as far as the content may name it: never returned, only compared. */
    record Payer(String name, String taxCode) {
    }

    /** One suggestion: the installment, its score and why, the reasons in the order of {@link Reason}. */
    record Suggestion(PayableInstallment installment, int score, List<Reason> reasons) {

        Suggestion {
            reasons = List.copyOf(reasons);
        }
    }

    private static final int NAMED = 60;
    private static final int CONTRACT = 45;
    private static final int ORDER = 40;
    private static final int PLACE = 15;
    private static final int TAX_CODE = 35;
    private static final int NAME = 25;
    private static final int EXACT = 30;
    private static final int WITHIN = 10;
    private static final int ABOVE = -10;
    private static final int DUE = 5;

    private TransferMatchScorer() {
    }

    /**
     * @param amount     what the transfer brought
     * @param content    its content as the bank wrote it
     * @param candidates the payable installments ({@link BankTransferReview#candidates})
     * @param payers     the candidates' customers by id; one missing is never named in the content
     * @param today      the business day: what "due soon" counts from
     */
    static List<Suggestion> suggest(BigDecimal amount, String content, List<BankTransferReview.Candidate> candidates,
                                    Map<UUID, Payer> payers, LocalDate today) {
        TransferText text = TransferText.of(content);
        List<Suggestion> scored = new ArrayList<>();
        for (BankTransferReview.Candidate candidate : candidates) {
            Suggestion suggestion = score(amount, text, candidate, payers.get(candidate.installment().customerId()),
                    today);
            if (suggestion.score() >= FLOOR) {
                scored.add(suggestion);
            }
        }
        scored.sort(Comparator.comparingInt(Suggestion::score).reversed()
                .thenComparing(s -> s.installment().daysOverdue(), Comparator.reverseOrder())
                .thenComparing(s -> s.installment().dueDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.installment().contractNumber())
                .thenComparingInt(s -> s.installment().seq()));
        return scored.size() > MAX_SUGGESTIONS ? List.copyOf(scored.subList(0, MAX_SUGGESTIONS)) : List.copyOf(scored);
    }

    static Suggestion score(BigDecimal amount, TransferText text, BankTransferReview.Candidate candidate, Payer payer,
                            LocalDate today) {
        PayableInstallment i = candidate.installment();
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        int score = 0;
        boolean document = false;
        if (candidate.namedInContent()) {
            reasons.add(Reason.CONTRACT_NUMBER_IN_CONTENT);
            score += NAMED;
        } else if (text.namesContract(i.contractNumber())) {
            reasons.add(Reason.CONTRACT_NUMBER_IN_CONTENT);
            score += CONTRACT;
            document = true;
        } else if (text.namesOrder(i.orderNumber())) {
            reasons.add(Reason.ORDER_NUMBER_IN_CONTENT);
            score += ORDER;
            document = true;
        }
        if (document && text.namesInstallment(i.transferReference())) {
            reasons.add(Reason.INSTALLMENT_IN_CONTENT);
            score += PLACE;
        }
        if (payer != null && text.namesTaxCode(payer.taxCode())) {
            reasons.add(Reason.TAX_CODE_IN_CONTENT);
            score += TAX_CODE;
        }
        if (payer != null && text.namesCustomer(payer.name())) {
            reasons.add(Reason.CUSTOMER_NAME_IN_CONTENT);
            score += NAME;
        }
        if (amount != null && amount.signum() > 0) {
            int compared = amount.compareTo(i.outstanding());
            if (compared == 0) {
                reasons.add(Reason.AMOUNT_EXACT);
                score += EXACT;
            } else if (compared < 0) {
                reasons.add(Reason.AMOUNT_WITHIN_DUE);
                score += WITHIN;
            } else {
                reasons.add(Reason.AMOUNT_ABOVE_DUE);
                score += ABOVE;
            }
        }
        if (i.overdue()) {
            reasons.add(Reason.OVERDUE);
            score += DUE;
        } else if (i.dueDate() != null && !i.dueDate().isBefore(today)
                && !i.dueDate().isAfter(today.plusDays(DUE_SOON_DAYS))) {
            reasons.add(Reason.DUE_SOON);
            score += DUE;
        }
        return new Suggestion(i, Math.max(0, Math.min(100, score)), List.copyOf(reasons));
    }

    /**
     * The best two of {@code best} (as {@link #suggest} orders them) score the same: the figures do not say which it
     * pays, whatever the order shows.
     */
    static boolean ambiguous(List<Suggestion> best) {
        return best.size() >= 2 && best.get(0).score() == best.get(1).score();
    }
}
