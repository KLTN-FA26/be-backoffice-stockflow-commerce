package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One round of a production order's print sample (4A-4, kltn-docs 17 §4.3 step 9): the photos and videos the
 * coordinator uploaded of what the producer printed, the day a physical sample was sent to the customer if one was, and
 * the customer's one answer - approved, or printed again with a note (13 BR-13). Round 2 on carries why it was printed
 * again ({@link ReprintReason}). Its rules across rounds - which may be opened, which answered - are {@link SampleRounds}'.
 *
 * <p>The database holds the same rules ({@code ordering.check_production_order_sample_round}, the CHECKs of
 * {@code production_order_sample_round}).</p>
 */
public final class SampleRound {

    /** The customer's answer. */
    public enum Result {
        APPROVED,
        REPRINT_REQUESTED
    }

    /** A photo or video of the round, in the object store. */
    public record Media(UUID id, String storageKey, String fileName, String contentType, long sizeBytes,
                        UUID uploadedBy, Instant uploadedAt) {

        public Media {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(contentType, "contentType");
        }

        public boolean video() {
            return contentType.startsWith("video/");
        }
    }

    private final UUID id;
    private final UUID productionOrderId;
    private final int roundNo;
    private final ReprintReason reprintReason;
    private final UUID openedBy;
    private final Instant openedAt;
    private LocalDate physicalSampleSentOn;
    private Result result;
    private String customerNote;
    private UUID answeredBy;
    private Instant answeredAt;
    private final List<Media> media;

    public SampleRound(UUID id, UUID productionOrderId, int roundNo, ReprintReason reprintReason, UUID openedBy,
                       Instant openedAt, LocalDate physicalSampleSentOn, Result result, String customerNote,
                       UUID answeredBy, Instant answeredAt, List<Media> media) {
        this.id = Objects.requireNonNull(id, "id");
        this.productionOrderId = Objects.requireNonNull(productionOrderId, "productionOrderId");
        if (roundNo < 1) {
            throw new IllegalArgumentException("Rounds are numbered from 1");
        }
        if ((roundNo == 1) != (reprintReason == null)) {
            throw new IllegalArgumentException("Round 1 is no reprint; every later round says why it is one");
        }
        this.roundNo = roundNo;
        this.reprintReason = reprintReason;
        this.openedBy = Objects.requireNonNull(openedBy, "openedBy");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.physicalSampleSentOn = physicalSampleSentOn;
        this.result = result;
        this.customerNote = customerNote;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        this.media = new ArrayList<>(media);
    }

    /** Still waiting for the customer's answer. */
    public boolean awaitingAnswer() {
        return result == null;
    }

    /**
     * Whether it counts against the contract's free sample rounds (17 §4.3 step 9, 2026-11-05): round 1 always, a reprint
     * when the customer changed their mind - never one the producer printed wrong.
     */
    public boolean counted() {
        return roundNo == 1 || reprintReason.counted();
    }

    void answer(Result answer, String note, UUID by, Instant at) {
        this.result = Objects.requireNonNull(answer, "answer");
        this.customerNote = note;
        this.answeredBy = Objects.requireNonNull(by, "by");
        this.answeredAt = Objects.requireNonNull(at, "at");
    }

    void physicalSampleSent(LocalDate on) {
        this.physicalSampleSentOn = on;
    }

    void add(List<Media> more) {
        media.addAll(more);
    }

    public UUID id() { return id; }
    public UUID productionOrderId() { return productionOrderId; }
    public int roundNo() { return roundNo; }
    public ReprintReason reprintReason() { return reprintReason; }
    public UUID openedBy() { return openedBy; }
    public Instant openedAt() { return openedAt; }
    public LocalDate physicalSampleSentOn() { return physicalSampleSentOn; }
    public Result result() { return result; }
    public String customerNote() { return customerNote; }
    public UUID answeredBy() { return answeredBy; }
    public Instant answeredAt() { return answeredAt; }
    public List<Media> media() { return Collections.unmodifiableList(media); }
}
