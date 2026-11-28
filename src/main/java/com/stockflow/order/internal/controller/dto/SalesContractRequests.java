package com.stockflow.order.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the sales contract endpoints (4B-4). The rules over the whole schedule and plan are the service's. */
public final class SalesContractRequests {

    private SalesContractRequests() {
    }

    /**
     * Drawing up (every field optional: what is left out starts from the defaults) or editing a draft ({@code version}
     * required; a schedule or plan sent replaces the old one whole, one left out is kept).
     *
     * <p>The contract's own terms are optional too, and kept when left out: {@code specialTerms} (a blank text clears
     * it), {@code penaltyPercent} (0 to 8), {@code quantityTolerancePercent} (0 to 10) and {@code warrantyMonths} (0 to
     * 36) - null reads the configured one, and writing the configured one down is the same - and the two signers, each
     * replaced whole when sent. The services check the ranges and say which rule a value breaks.</p>
     */
    public record Draft(
            Long version,
            @PositiveOrZero Integer freeSampleRounds,
            @Valid @Size(max = 20) List<Batch> deliverySchedule,
            @Valid @Size(max = 20) List<Installment> paymentPlan,
            @jakarta.validation.constraints.Positive @jakarta.validation.constraints.Max(365)
            Integer returnWindowDays,
            @Size(max = 4100) String specialTerms,
            Integer penaltyPercent,
            Integer quantityTolerancePercent,
            Integer warrantyMonths,
            @Valid SellerSigner sellerSigner,
            @Valid BuyerSigner buyerSigner) {

        /** Without the contract's own terms. */
        public Draft(Long version, Integer freeSampleRounds, List<Batch> deliverySchedule,
                     List<Installment> paymentPlan, Integer returnWindowDays) {
            this(version, freeSampleRounds, deliverySchedule, paymentPlan, returnWindowDays, null, null, null, null,
                    null, null);
        }
    }

    /** The seller's signer: name, title and the authorization they sign under when not the legal representative. */
    public record SellerSigner(@Size(max = 300) String name, @Size(max = 300) String title,
                               @Size(max = 200) String authorizationRef) { }

    public record BuyerSigner(@Size(max = 300) String name, @Size(max = 300) String title) { }

    /** A manager's decision on a draft's terms: the version read, a note (the reason, when rejecting). */
    public record TermsDecision(@NotNull Long version, @Size(max = 600) String note) { }

    /** Moving the day the customer should have signed by. */
    public record SignBy(@NotNull Long version, @NotNull LocalDate signBy) { }

    /** The customer sends the contract back for changes: the version read and what they want changed (10 to 2000). */
    public record ChangeRequest(@NotNull Long version, @Size(max = 2100) String reason) { }

    /** A delivery batch, in delivery order: its window (both days or neither) and its cartons. */
    public record Batch(LocalDate windowStart, LocalDate windowEnd, @Valid @Size(max = 200) List<BatchLine> lines) { }

    public record BatchLine(@NotNull UUID orderLineId, @NotNull Integer cartons) { }

    /** An installment, in order: an amount or a percent of the total with tax, one of the two. */
    public record Installment(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String milestone,
            BigDecimal amount,
            BigDecimal percent,
            Integer dueDays,
            Integer deliveryBatchSeq) { }

    /** A transition of the contract as read. */
    public record Version(@NotNull Long version) { }

    /** Recording the contract signed: the uploaded signed file and the day it was signed. */
    public record Signature(@NotNull Long version, @NotNull UUID uploadId, @NotNull LocalDate signedOn) { }

    /** Cancelling the contract before it is signed. */
    public record Cancellation(@NotNull Long version, @NotBlank @Size(max = 500) String reason) { }

    /**
     * 15 BR-11: accounting's decision on a contract waiting before it is sent, against the version read - the note an
     * approval keeps, or the reason a refusal gives Sales.
     */
    public record CreditDecision(@NotNull Long version, @NotBlank @Size(max = 500) String note) { }

    /**
     * The customer signs with the code mailed to them: the version read, the six digits, who they are as a signer and
     * that they agree to sign electronically (QĐ-3).
     */
    public record CustomerSign(@NotNull Long version, @NotBlank @jakarta.validation.constraints.Pattern(
            regexp = "\\d{6}") String otp, @NotBlank @Size(max = 200) String signerName,
                               @NotBlank @Size(max = 200) String signerTitle, @NotNull Boolean consent) { }

    /** The company's signer signs: the version read, and who signs when it is not the contract's own seller signer. */
    public record CompanySign(@NotNull Long version, @Size(max = 200) String signerName,
                              @Size(max = 200) String signerTitle) { }
}
