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
     */
    public record Draft(
            Long version,
            @PositiveOrZero Integer freeSampleRounds,
            @Valid @Size(max = 100) List<Batch> deliverySchedule,
            @Valid @Size(max = 100) List<Installment> paymentPlan) { }

    /** A delivery batch, in delivery order: its window (both days or neither) and its cartons. */
    public record Batch(LocalDate windowStart, LocalDate windowEnd, @Valid @Size(max = 500) List<BatchLine> lines) { }

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
}
