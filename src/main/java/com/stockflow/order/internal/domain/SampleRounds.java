package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The print sample rounds of one production order, and the rules across them (4A-4; kltn-docs 17 §4.3 steps 9-10,
 * §5.3, BR-24; 13 BR-13; 14 BR-18):
 * <ul>
 *   <li>Rounds belong to a production order that wants a print sample. Round 1 is opened - its photos or videos
 *       uploaded - once the producer accepted the order, and that moves it to SAMPLE_PENDING; each later round only
 *       after the customer asked for the round before to be printed again, and says why it was ({@link ReprintReason},
 *       the coordinator's call). So at most one round waits for the customer, always the latest.</li>
 *   <li>The customer who owns the production order answers that round once (13 BR-13): approved - which starts mass
 *       printing, SAMPLE_PENDING → IN_PRODUCTION - or printed again with a note saying what to change. Nobody else's
 *       answer is taken; another customer is told there is no such round.</li>
 *   <li>Rounds are counted against the contract's free sample rounds - round 1 and every {@code CUSTOMER_CHANGE}
 *       reprint, never a {@code PRINT_DEFECT} - and going past them is a warning for the coordinator and Sales, never a
 *       refusal ({@link #allowance}).</li>
 * </ul>
 * The production order's own moves stay its own ({@link ProductionOrder#awaitSample}, {@link ProductionOrder#sampleApproved});
 * this class is the only caller, so a round and its production order move together or not at all.
 */
public final class SampleRounds {

    /** The most photos and videos added to a round at once. */
    public static final int MEDIA_PER_UPLOAD_MAX = 10;

    /**
     * The contract's free sample rounds against those counted so far (14 BR-18).
     *
     * @param free null when the order's contract is no longer there to say
     */
    public record Allowance(Integer free, int counted) {

        /** Past the free rounds: warn the coordinator and Sales (17 §4.3 step 9), never refuse. */
        public boolean exceeded() {
            return free != null && counted > free;
        }
    }

    private final UUID productionOrderId;
    private final List<SampleRound> rounds;

    public SampleRounds(UUID productionOrderId, List<SampleRound> rounds) {
        this.productionOrderId = Objects.requireNonNull(productionOrderId, "productionOrderId");
        if (rounds.stream().anyMatch(round -> !round.productionOrderId().equals(productionOrderId))) {
            throw new IllegalArgumentException("Rounds of another production order");
        }
        this.rounds = new ArrayList<>(rounds);
        this.rounds.sort(Comparator.comparingInt(SampleRound::roundNo));
    }

    /**
     * Opens the next round with {@code media}. Round 1 moves {@code order} from ACCEPTED to SAMPLE_PENDING.
     *
     * @param reason   why it is printed again: null for round 1, required after
     * @param sentOn   the day a physical sample went to the customer, if one did - not after {@code today}
     * @throws BusinessException {@code PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED}; {@code PRODUCTION_ORDER_STATUS_INVALID}
     *         for round 1 of an order not ACCEPTED, or a later round of one not SAMPLE_PENDING;
     *         {@code SAMPLE_ROUND_NOT_OPENABLE} while the latest round was not sent back by the customer;
     *         {@code VALIDATION_FAILED} with no photo or video, too many, a reason missing or not wanted, a date ahead
     */
    public SampleRound open(ProductionOrder order, ReprintReason reason, LocalDate sentOn, List<SampleRound.Media> media,
                            UUID by, Instant at, LocalDate today) {
        requireOwn(order);
        if (!order.sampleApprovalRequired()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED, ("Production order %s has no "
                    + "print sample to approve (BR-24)").formatted(order.code()));
        }
        requireMedia(media);
        requireNotAhead(sentOn, today);
        Optional<SampleRound> latest = latest();
        int number;
        if (latest.isEmpty()) {
            if (order.status() != ProductionOrderStatus.ACCEPTED) {
                throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: its "
                        + "first print sample is uploaded once the producer accepted it (17 §5.3)")
                        .formatted(order.code(), order.status()));
            }
            if (reason != null) {
                throw invalid("Round 1 is no reprint: it has no reprint reason");
            }
            number = 1;
        } else {
            if (order.status() != ProductionOrderStatus.SAMPLE_PENDING) {
                throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: a new "
                        + "print sample round is opened while its sample is pending").formatted(order.code(),
                        order.status()));
            }
            if (latest.get().result() != SampleRound.Result.REPRINT_REQUESTED) {
                throw new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_OPENABLE, ("Production order %s: round %d %s; a "
                        + "new round follows the customer's request to print it again").formatted(order.code(),
                        latest.get().roundNo(), latest.get().awaitingAnswer() ? "still waits for the customer"
                                : "was approved"));
            }
            if (reason == null) {
                throw invalid("A round printed again says why: PRINT_DEFECT or CUSTOMER_CHANGE (17 §4.3 step 9)");
            }
            number = latest.get().roundNo() + 1;
        }
        SampleRound opened = new SampleRound(Identifiers.newId(), productionOrderId, number, reason,
                Objects.requireNonNull(by, "by"), Objects.requireNonNull(at, "at"), sentOn, null, null, null, null,
                media);
        if (number == 1) {
            order.awaitSample(by, at);
        }
        rounds.add(opened);
        return opened;
    }

    /**
     * The customer's answer to round {@code roundId} (13 BR-13): only the customer who owns the production order, only
     * while it is SAMPLE_PENDING and the round waits, once. Approval starts mass printing.
     *
     * @param customerId the customer answering - another customer's production order has no such round for them
     * @param note       what to change: required to ask for a reprint, not kept with an approval
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND}; {@code SAMPLE_ROUND_ANSWERED};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID} once the production order is no longer pending (cancelled);
     *         {@code VALIDATION_FAILED} for a reprint request without a note
     */
    public SampleRound answer(ProductionOrder order, UUID customerId, UUID roundId, SampleRound.Result answer,
                              String note, UUID by, Instant at) {
        requireOwn(order);
        SampleRound round = rounds.stream().filter(r -> r.id().equals(roundId)).findFirst()
                .filter(r -> order.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_FOUND, "No print sample round "
                        + roundId));
        if (!round.awaitingAnswer()) {
            throw new BusinessException(ErrorCode.SAMPLE_ROUND_ANSWERED, ("Round %d of production order %s was answered "
                    + "already: %s (13 BR-13)").formatted(round.roundNo(), order.code(), round.result()));
        }
        if (order.status() != ProductionOrderStatus.SAMPLE_PENDING) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: its print "
                    + "sample is no longer waiting for an answer").formatted(order.code(), order.status()));
        }
        String kept = null;
        if (Objects.requireNonNull(answer, "answer") == SampleRound.Result.REPRINT_REQUESTED) {
            kept = note == null || note.isBlank() ? null : note.strip();
            if (kept == null) {
                throw invalid("Asking for the sample to be printed again says what to change (13 BR-13)");
            }
            if (kept.length() > ProductionOrder.TEXT_MAX) {
                throw invalid("A note is at most %d characters".formatted(ProductionOrder.TEXT_MAX));
            }
        }
        round.answer(answer, kept, by, at);
        if (answer == SampleRound.Result.APPROVED) {
            order.sampleApproved(round.roundNo(), by, at);
        }
        return round;
    }

    /**
     * Photos or videos added to the round still waiting for the customer: what they answer on is what was there.
     *
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND}; {@code SAMPLE_ROUND_ANSWERED};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED} with none or too many
     */
    public SampleRound addMedia(ProductionOrder order, UUID roundId, List<SampleRound.Media> media) {
        SampleRound round = waitingFor(order, roundId);
        requireMedia(media);
        round.add(media);
        return round;
    }

    /**
     * The day a physical sample of the round went to the customer (17 §4.3 step 9: optional), while the round waits for
     * the answer.
     *
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND}; {@code SAMPLE_ROUND_ANSWERED};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED} for no day or one ahead of today
     */
    public SampleRound physicalSampleSent(ProductionOrder order, UUID roundId, LocalDate on, LocalDate today) {
        SampleRound round = waitingFor(order, roundId);
        if (on == null) {
            throw invalid("The day the physical sample was sent is required");
        }
        requireNotAhead(on, today);
        round.physicalSampleSent(on);
        return round;
    }

    /** The rounds counted so far against {@code free}, the contract's free sample rounds (null when unknown). */
    public Allowance allowance(Integer free) {
        return new Allowance(free, (int) rounds.stream().filter(SampleRound::counted).count());
    }

    /** The round waiting for the customer, if one does. */
    public Optional<SampleRound> waiting() {
        return latest().filter(SampleRound::awaitingAnswer);
    }

    public Optional<SampleRound> latest() {
        return rounds.isEmpty() ? Optional.empty() : Optional.of(rounds.get(rounds.size() - 1));
    }

    public Optional<SampleRound> find(UUID roundId) {
        return rounds.stream().filter(round -> round.id().equals(roundId)).findFirst();
    }

    public List<SampleRound> rounds() {
        return List.copyOf(rounds);
    }

    public UUID productionOrderId() {
        return productionOrderId;
    }

    /**
     * The round {@code roundId}, while it waits for the customer and its production order is SAMPLE_PENDING - what may
     * still change about it (photos added, a physical sample recorded).
     *
     * @throws BusinessException {@code SAMPLE_ROUND_NOT_FOUND}; {@code SAMPLE_ROUND_ANSWERED};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}
     */
    public SampleRound waitingFor(ProductionOrder order, UUID roundId) {
        requireOwn(order);
        SampleRound round = find(roundId).orElseThrow(() -> new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_FOUND,
                "No print sample round " + roundId));
        if (!round.awaitingAnswer()) {
            throw new BusinessException(ErrorCode.SAMPLE_ROUND_ANSWERED, ("Round %d of production order %s was answered: "
                    + "it stays as the customer saw it").formatted(round.roundNo(), order.code()));
        }
        if (order.status() != ProductionOrderStatus.SAMPLE_PENDING) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s: its print "
                    + "sample is no longer pending").formatted(order.code(), order.status()));
        }
        return round;
    }

    private void requireOwn(ProductionOrder order) {
        if (!order.id().equals(productionOrderId)) {
            throw new IllegalArgumentException("Rounds of another production order");
        }
    }

    private static void requireMedia(List<SampleRound.Media> media) {
        if (media == null || media.isEmpty()) {
            throw invalid("A print sample round shows at least one photo or video (17 §4.3 step 9)");
        }
        if (media.size() > MEDIA_PER_UPLOAD_MAX) {
            throw invalid("At most %d photos or videos at once".formatted(MEDIA_PER_UPLOAD_MAX));
        }
    }

    private static void requireNotAhead(LocalDate on, LocalDate today) {
        if (on != null && on.isAfter(today)) {
            throw invalid("A physical sample is recorded once it was sent: %s is ahead of today".formatted(on));
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
