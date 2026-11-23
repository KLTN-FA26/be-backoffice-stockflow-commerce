package com.stockflow.payment.internal.service;

import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.domain.SettlementBook;
import com.stockflow.payment.internal.repository.CustomerLedgerQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Draws the papers about money a customer is handed: the confirmation of a payment received, the confirmation of a
 * refund paid, and the record reconciling what the customer owes. Each is drawn when asked for, from the books as they
 * stand - none is stored, so a receipt voided since simply has no confirmation any more.
 *
 * <p>Every reader takes {@code ownCustomerId}: null for the back office, the caller's customer for the portal, and a
 * paper of anyone else's is then not found, as the installment and the refund themselves are.</p>
 *
 * <p>A confirmation's number is made from what it confirms - the statement's day and the record's id - not drawn from
 * a sequence: the same receipt printed twice is the same paper, and nothing has to be written to print it.</p>
 */
@Service
public class PaymentPaperService {

    /** A drawn paper and the name its download carries. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final DateTimeFormatter NUMBER_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final InstallmentService installments;
    private final InstallmentBook book;
    private final SettlementBook settlements;
    private final CustomerLedgerQuery ledger;
    private final CustomerService customers;
    private final CustomerLegalProfiles legalProfiles;
    private final IdentityService identities;
    private final PaymentConfirmationDocument paymentConfirmation;
    private final RefundConfirmationDocument refundConfirmation;
    private final DebtReconciliationDocument debtReconciliation;
    private final Clock clock;

    PaymentPaperService(InstallmentService installments, InstallmentBook book, SettlementBook settlements,
                        CustomerLedgerQuery ledger, CustomerService customers, CustomerLegalProfiles legalProfiles,
                        IdentityService identities, PaymentConfirmationDocument paymentConfirmation,
                        RefundConfirmationDocument refundConfirmation, DebtReconciliationDocument debtReconciliation,
                        Clock clock) {
        this.installments = installments;
        this.book = book;
        this.settlements = settlements;
        this.ledger = ledger;
        this.customers = customers;
        this.legalProfiles = legalProfiles;
        this.identities = identities;
        this.paymentConfirmation = paymentConfirmation;
        this.refundConfirmation = refundConfirmation;
        this.debtReconciliation = debtReconciliation;
        this.clock = clock;
    }

    /**
     * The confirmation of one receipt's payment of one installment.
     *
     * @throws BusinessException {@code INSTALLMENT_NOT_FOUND}; {@code PAYMENT_RECEIPT_NOT_FOUND} when the receipt did
     *         not pay this installment, or was voided
     */
    @Transactional(readOnly = true)
    public Pdf paymentConfirmation(UUID installmentId, UUID receiptId, UUID ownCustomerId) {
        InstallmentService.InstallmentDetail detail = installments.detail(installmentId, ownCustomerId);
        Installment installment = detail.installment();
        InstallmentBook.ReceiptOfInstallment paid = detail.receipts().stream()
                .filter(receipt -> receipt.receiptId().equals(receiptId)
                        && receipt.status() == PaymentReceiptStatus.RECORDED)
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_RECEIPT_NOT_FOUND,
                        "No receipt %s in force paid installment %s".formatted(receiptId, installmentId)));
        UUID recordedBy = book.findReceipt(receiptId).map(PaymentReceipt::recordedBy).orElse(null);
        ResourceBundle t = DocumentParts.bundle();
        InstallmentStanding standing = installments.standing(installment.orderId());
        String number = "XNTT-" + NUMBER_DAY.format(paid.receivedOn()) + "-" + tail(receiptId) + "-"
                + installment.seq();
        byte[] pdf = paymentConfirmation.render(new PaymentConfirmationDocument.Facts(number,
                LocalDate.ofInstant(paid.recordedAt(), InstallmentService.BUSINESS_ZONE),
                party(installment.customerId()), installment.contractNumber(), installment.orderNumber(),
                label(t, installment), paid.amount(), paid.receivedOn(), paid.recordedAt(), paid.reference(),
                standing.total(), standing.paid(), nameOf(recordedBy)));
        return new Pdf(t.getString("document.file.paymentConfirmation").formatted(number), pdf);
    }

    /**
     * The confirmation of one refund recorded.
     *
     * @throws BusinessException {@code REFUND_RECORD_NOT_FOUND}; {@code REFUND_RECORD_ALREADY_VOIDED} for a record
     *         voided - a refund that was not made is not confirmed
     */
    @Transactional(readOnly = true)
    public Pdf refundConfirmation(UUID recordId, UUID ownCustomerId) {
        RefundRecord record = settlements.findRecord(recordId)
                .filter(found -> ownCustomerId == null || ownCustomerId.equals(found.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_RECORD_NOT_FOUND,
                        "No refund record " + recordId));
        if (record.status() == RefundRecord.Status.VOIDED) {
            throw new BusinessException(ErrorCode.REFUND_RECORD_ALREADY_VOIDED,
                    "Refund record %s was voided".formatted(record.reference()));
        }
        ResourceBundle t = DocumentParts.bundle();
        List<OrderSettlement> ofOrder = settlements.ofOrder(record.orderId());
        String reason = ofOrder.stream().filter(settlement -> settlement.refundable().signum() > 0)
                .map(settlement -> settlement.kind().name()).distinct()
                .map(kind -> "payment-papers.refund." + kind)
                .map(key -> t.getString(t.containsKey(key) ? key : "payment-papers.refund.other"))
                .collect(Collectors.joining("; "));
        String contractNumber = ofOrder.stream().map(OrderSettlement::contractNumber).findFirst()
                .orElseGet(() -> book.ofOrder(record.orderId()).stream().map(Installment::contractNumber).findFirst()
                        .orElse(""));
        String account = legalProfiles.legalProfile(record.customerId()).map(CustomerLegalProfile::bankAccount)
                .filter(bank -> DocumentParts.filled(bank.accountNumber()) && DocumentParts.filled(bank.bankName()))
                .map(bank -> DocumentParts.filled(bank.accountHolder())
                        ? t.getString("payment-papers.account").formatted(bank.accountNumber(), bank.bankName(),
                        bank.accountHolder())
                        : t.getString("document.party.accountAt").formatted(bank.accountNumber(), bank.bankName()))
                .orElse(null);
        String number = "PHT-" + NUMBER_DAY.format(record.refundedOn()) + "-" + tail(recordId);
        byte[] pdf = refundConfirmation.render(new RefundConfirmationDocument.Facts(number,
                LocalDate.ofInstant(record.recordedAt(), InstallmentService.BUSINESS_ZONE), party(record.customerId()),
                reason.isEmpty() ? t.getString("payment-papers.refund.ORDER_CANCELLED") : reason, null, null,
                contractNumber, record.orderNumber(), record.amount(), account, record.refundedOn(),
                record.reference(), nameOf(record.recordedBy())));
        return new Pdf(t.getString("document.file.refundConfirmation").formatted(number), pdf);
    }

    /**
     * The record reconciling a customer's debt over {@code from} to {@code to}, both days included.
     *
     * <p>The ledger is made of what the books hold. An installment adds to the debt the day its milestone is reached,
     * by what it charges after reductions; a receipt takes its lines off the day the bank statement gives; a refund
     * adds back what was paid out. A settlement is entered so the three add up: a cancellation writes off the
     * installments charged so far and charges the fee kept of the money collected; a closure of the rest or a return
     * takes off what it made refundable, the reductions being in the installments already. The opening balance is all
     * of that before {@code from}. A reduction is dated with its installment, not with the day it was made - the books
     * keep the amount, not a history of it.</p>
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code VALIDATION_FAILED} for a period that ends before it
     *         starts or after today
     */
    @Transactional(readOnly = true)
    public Pdf debtReconciliation(UUID customerId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(InstallmentService.BUSINESS_ZONE));
        if (from == null || to == null || to.isBefore(from) || to.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reconciliation covers a period from a day to a later one, not after today");
        }
        CustomerSummary customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        ResourceBundle t = DocumentParts.bundle();
        List<CustomerLedgerQuery.Charge> charges = ledger.charges(customerId);
        List<DebtReconciliationDocument.Entry> all = new ArrayList<>();
        for (CustomerLedgerQuery.Charge charge : charges) {
            String what = charge.feeItem()
                    ? t.getString("payment-papers.debt.fee").formatted(
                    t.getString("payment-papers.fee." + charge.milestone()), charge.orderNumber())
                    : t.getString("payment-papers.debt.charge").formatted(charge.seq(), charge.name(),
                    charge.orderNumber());
            all.add(entry(charge.day(), charge.contractNumber(), what, charge.amount(), BigDecimal.ZERO));
        }
        for (CustomerLedgerQuery.Receipt receipt : ledger.receipts(customerId)) {
            String what = receipt.feeItem()
                    ? t.getString("payment-papers.debt.receiptFee").formatted(receipt.contractNumber())
                    : t.getString("payment-papers.debt.receipt").formatted(receipt.seq(), receipt.contractNumber());
            all.add(entry(receipt.day(), receipt.reference(), what, BigDecimal.ZERO, receipt.amount()));
        }
        for (CustomerLedgerQuery.Settlement settlement : ledger.settlements(customerId)) {
            if (OrderSettlement.Kind.ORDER_CANCELLED.name().equals(settlement.kind())) {
                BigDecimal charged = charges.stream().filter(charge -> !charge.feeItem()
                                && charge.orderNumber().equals(settlement.orderNumber()))
                        .map(CustomerLedgerQuery.Charge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (charged.signum() > 0) {
                    all.add(entry(settlement.day(), settlement.contractNumber(), t.getString(
                            "payment-papers.debt.cancelled").formatted(settlement.orderNumber()), BigDecimal.ZERO,
                            charged));
                }
                if (settlement.feeCovered().signum() > 0) {
                    all.add(entry(settlement.day(), settlement.contractNumber(), t.getString(
                            "payment-papers.debt.cancellationFee").formatted(settlement.orderNumber()),
                            settlement.feeCovered(), BigDecimal.ZERO));
                }
            } else if (settlement.refundable().signum() > 0) {
                // A kind this paper has no words for yet reads as the plain reduction it is, not as a 500.
                String key = switch (settlement.kind()) {
                    case "GOODS_RETURNED" -> "payment-papers.debt.returned";
                    case "SHIPMENT_RETURNED" -> "payment-papers.debt.shipmentReturned";
                    case "REMAINDER_CLOSED" -> "payment-papers.debt.closed";
                    default -> "payment-papers.debt.reduced";
                };
                all.add(entry(settlement.day(), settlement.contractNumber(), t.getString(key)
                        .formatted(settlement.orderNumber()), BigDecimal.ZERO, settlement.refundable()));
            }
        }
        for (CustomerLedgerQuery.Refund refund : ledger.refunds(customerId)) {
            all.add(entry(refund.day(), refund.reference(), t.getString("payment-papers.debt.refund")
                    .formatted(refund.orderNumber()), refund.amount(), BigDecimal.ZERO));
        }
        // Stable: entries of one day keep the order they were gathered in - charged, paid, settled, refunded.
        all.sort(Comparator.comparing(DebtReconciliationDocument.Entry::date));

        BigDecimal opening = all.stream().filter(entry -> entry.date().isBefore(from))
                .map(entry -> entry.debit().subtract(entry.credit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<DebtReconciliationDocument.Entry> period = all.stream()
                .filter(entry -> !entry.date().isBefore(from) && !entry.date().isAfter(to)).toList();
        // Overdue is a fact of today: a period that ended earlier cannot say what was overdue on its last day.
        BigDecimal overdue = to.equals(today) ? charges.stream()
                .filter(charge -> charge.dueDate() != null && charge.dueDate().isBefore(today)
                        && charge.outstanding().signum() > 0)
                .map(CustomerLedgerQuery.Charge::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        Set<String> contracts = charges.stream().map(CustomerLedgerQuery.Charge::contractNumber)
                .collect(Collectors.toSet());
        String number = "BBDCCN-" + customer.code() + "-" + DateTimeFormatter.BASIC_ISO_DATE.format(to);
        byte[] pdf = debtReconciliation.render(new DebtReconciliationDocument.Facts(number, today, from, to,
                contracts.size() == 1 ? contracts.iterator().next() : null, party(customerId), opening, period,
                overdue));
        return new Pdf(t.getString("document.file.debtReconciliation").formatted(number), pdf);
    }

    private static DebtReconciliationDocument.Entry entry(LocalDate day, String reference, String description,
                                                          BigDecimal debit, BigDecimal credit) {
        return new DebtReconciliationDocument.Entry(day, reference, description, debit, credit);
    }

    /** "Đợt 2/3 - Thanh toán trước khi giao hàng"; a fee item by what it is a fee for. */
    private String label(ResourceBundle t, Installment installment) {
        if (installment.isFeeItem()) {
            return t.getString("payment-papers.fee." + installment.milestone().name());
        }
        long planned = book.ofOrder(installment.orderId()).stream().filter(other -> !other.isFeeItem()).count();
        return t.getString("payment-papers.installment").formatted(installment.seq(), planned, installment.name());
    }

    /** The customer as its legal profile names it; one without a profile yet, by the name and tax code it has. */
    private DocumentParts.Party party(UUID customerId) {
        return legalProfiles.legalProfile(customerId)
                .map(profile -> new DocumentParts.Party(profile.companyName(), profile.taxCode(),
                        profile.registeredAddress(), profile.representativeName(), profile.representativeTitle()))
                .orElseGet(() -> customers.findById(customerId)
                        .map(customer -> new DocumentParts.Party(customer.fullName(), customer.taxCode(), null, null,
                                null))
                        .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)));
    }

    private String nameOf(UUID userId) {
        return userId == null ? null : identities.displayNames(List.of(userId)).get(userId);
    }

    /** The end of a UUIDv7 - its random part - short enough to read out over the phone. */
    private static String tail(UUID id) {
        String hex = id.toString().replace("-", "");
        return hex.substring(hex.length() - 6).toUpperCase(Locale.ROOT);
    }
}
