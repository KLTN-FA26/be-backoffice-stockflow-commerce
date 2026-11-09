package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The sales contract of an order (4B-4; kltn-docs 14 §4.3 steps 11-17, §5.2, BR-12, BR-13, BR-14, BR-18, BR-23; 15
 * BR-11, BR-12, BR-18; 18 BR-05). Sales draws it up for a draft order, edits its delivery schedule, payment plan and
 * free print-sample rounds while it is a draft, sends it - the PDF the customer receives is kept - records it signed
 * with the signed file, or cancels it with a reason to draw up another. Accounting and the Sales Manager read it; the
 * customer reads a contract once it is sent.
 */
public interface SalesContracts {

    /**
     * What Sales enters. On drawing up, anything left out starts from the defaults: the configured free sample rounds,
     * one delivery batch of every carton in the window the customer asked for, the customer's default payment schedule.
     * On an edit, the schedule and the plan are replaced whole.
     */
    record Draft(Integer freeSampleRounds, List<Batch> deliverySchedule, List<Installment> paymentPlan) { }

    /** A delivery batch as entered, in delivery order. */
    record Batch(LocalDate windowStart, LocalDate windowEnd, List<BatchLine> lines) { }

    /** Cartons of one order line in a batch. */
    record BatchLine(UUID orderLineId, Integer cartons) { }

    /**
     * An installment as entered, in order: an amount or a percent of the order's total with tax.
     *
     * @param milestone        ON_SIGNING, BEFORE_DELIVERY or AFTER_DELIVERY
     * @param deliveryBatchSeq the delivery batch it is tied to, by number; never for ON_SIGNING
     */
    record Installment(String name, String milestone, BigDecimal amount, BigDecimal percent, Integer dueDays,
                       Integer deliveryBatchSeq) { }

    /** A party as the contract names it. */
    record Party(String name, String taxCode, String address, String representative, String representativeTitle) { }

    /** The order's money as the contract shows it - the order's, never stored twice (14 BR-12). */
    record Totals(BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount, String currency) { }

    /** @param value the batch's goods with their VAT: what an installment tied to it starts from (14 BR-13) */
    record BatchView(int seq, LocalDate windowStart, LocalDate windowEnd, BigDecimal value, List<BatchLineView> lines) { }

    /** @param units the pieces: cartons x the line's pieces per carton */
    record BatchLineView(UUID orderLineId, String sku, int cartons, int units) { }

    record InstallmentView(int seq, String name, String milestone, BigDecimal percent, BigDecimal amount,
                           Integer dueDays, Integer deliveryBatchSeq) { }

    /**
     * 15 BR-11: sending a contract that pays after delivery checks the customer's credit and warns, never refuses; the
     * order is held for accounting when the contract is signed if it still does not fit.
     *
     * @param outcome OVERDUE (the customer owes something overdue) or OVER_LIMIT
     */
    record CreditWarning(String outcome, BigDecimal creditLimit, BigDecimal exposure, BigDecimal afterDelivery) { }

    /**
     * @param creditWarning set only on the answer to a send that warned
     */
    record ContractView(UUID id, String contractNumber, UUID orderId, String orderNumber, String status, Party seller,
                        Party buyer, int freeSampleRounds, Totals totals, List<BatchView> deliverySchedule,
                        List<InstallmentView> paymentPlan, Instant sentAt, UUID sentBy, LocalDate signedOn,
                        String signedFileName, Instant signedAt, UUID signedBy, Instant cancelledAt,
                        UUID cancelledBy, String cancellationReason, Instant completedAt, long version,
                        CreditWarning creditWarning) { }

    /**
     * Draws up the order's contract (14 §4.3 step 11).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (no such order); {@code SALES_CONTRACT_EXISTS};
     *         {@code SALES_CONTRACT_ORDER_NOT_DRAFT}; {@code SALES_CONTRACT_ORDER_NOT_CARTONS};
     *         {@code CUSTOMER_LEGAL_PROFILE_MISSING}; {@code SELLER_PROFILE_UNAVAILABLE};
     *         {@code DELIVERY_SCHEDULE_INVALID}; {@code PAYMENT_PLAN_INVALID}; {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}
     */
    ContractView create(CurrentUser user, UUID orderId, Draft draft);

    /** The order's live contract. @throws com.stockflow.common.error.BusinessException {@code SALES_CONTRACT_NOT_FOUND} */
    ContractView get(UUID orderId);

    /** Every contract the order had, the cancelled ones too, newest first. */
    List<ContractView> history(UUID orderId);

    /** A draft revised against the version read (14 §4.3 step 16). */
    ContractView edit(CurrentUser user, UUID orderId, long version, Draft draft);

    /** Sent to the customer (14 §4.3 step 13); the answer carries the credit warning, if any. */
    ContractView send(CurrentUser user, UUID orderId, long version);

    /** Where the browser uploads the signed file (FileCategory.DOCUMENT); the contract is sent. */
    UploadSlot beginSignedFileUpload(CurrentUser user, UUID orderId, UploadSlotRequest request);

    /**
     * Recorded signed with the signed file - sent with the request, or uploaded through
     * {@link #beginSignedFileUpload} - and the day it was signed (14 §4.3 step 14, BR-14).
     */
    ContractView sign(CurrentUser user, UUID orderId, long version, IncomingFiles signedFile, LocalDate signedOn);

    /** Cancelled before it was signed, with a reason (14 §4.3 step 16). */
    ContractView cancel(CurrentUser user, UUID orderId, long version, String reason);

    /** The live contract drawn as a PDF now - what a draft would be sent as. */
    byte[] preview(UUID orderId);

    /** A link to the PDF the live contract was sent as. */
    DownloadLink sentDocument(UUID orderId);

    /** A link to the live contract's signed file. */
    DownloadLink signedFile(UUID orderId);

    /**
     * One of the customer's own orders' contract, once sent: a draft is Sales' work in progress. Someone else's order,
     * or no sent contract, is 404 either way.
     */
    ContractView customerContract(UUID customerId, UUID orderId);

    /** A link to the PDF the customer was sent, or to the signed file once signed. */
    DownloadLink customerDocument(UUID customerId, UUID orderId);
}
