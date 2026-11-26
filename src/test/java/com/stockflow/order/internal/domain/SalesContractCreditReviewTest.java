package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 15 BR-11 as changed on 2026-11-26: a draft whose credit check failed waits for accounting instead of being
 * sent - locked against edits and sends until approved (sent at once), refused or withdrawn (Sales' again).
 */
class SalesContractCreditReviewTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final UUID SALES = UUID.randomUUID();
    private static final ContractParty SELLER = new ContractParty("Seller", "0101", "1 A St", "Ms A", "Director");
    private static final ContractParty BUYER = new ContractParty("Buyer", null, "2 B St", "Mr B", "Owner");

    private final Order order = Order.draft(OrderNumber.of(TODAY, 7), UUID.randomUUID(), UUID.randomUUID(),
            List.of(Order.line(UUID.randomUUID(), new Sku("LY-A"), 1000, Money.vnd(2_000), null)
                    .withTax(BigDecimal.TEN).withCartons(UUID.randomUUID(), 20, 50)), NOW);

    private SalesContract contract() {
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(new ContractTerms.BatchEntry(
                TODAY.plusDays(20), TODAY.plusDays(22), List.of(new ContractTerms.LineEntry(order.lines().getFirst().id(),
                        20)))));
        List<PlannedInstallment> plan = ContractTerms.plan(order, 1, List.of(new ContractTerms.InstallmentEntry(
                "After delivery", PaymentMilestone.AFTER_DELIVERY, null, new BigDecimal("100"), 30, 1)), true, 30);
        return SalesContract.draft(UUID.randomUUID(), "HD-7", order, SELLER, BUYER, 2, schedule, plan);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("waiting, it is neither edited nor sent nor submitted again; still a DRAFT")
    void waitingLocksTheDraft() {
        SalesContract contract = contract();
        contract.submitForCreditReview(order, NOW);

        assertThat(contract.status()).isEqualTo(SalesContractStatus.DRAFT);
        assertThat(contract.creditReviewPending()).isTrue();
        assertThatThrownBy(() -> contract.revise(order, 1, contract.schedule(), contract.plan()))
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.agreeReturnWindow(order, 10))
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "x"))
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.submitForCreditReview(order, NOW))
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
    }

    @Test
    @DisplayName("approved, it is sent at once as the Sales account that asked")
    void approvedIsSent() {
        SalesContract contract = contract();
        assertThatThrownBy(() -> contract.approveCredit(order, NOW, SALES, "x"))
                .as("nothing waits").extracting(SalesContractCreditReviewTest::code)
                .isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_NOT_PENDING);
        contract.submitForCreditReview(order, NOW);

        contract.approveCredit(order, NOW, SALES, "documents/hd-7.pdf");

        assertThat(contract.status()).isEqualTo(SalesContractStatus.SENT);
        assertThat(contract.creditReviewPending()).isFalse();
        assertThat(contract.sentBy()).isEqualTo(SALES);
        assertThat(contract.sentDocumentKey()).isEqualTo("documents/hd-7.pdf");
    }

    @Test
    @DisplayName("approved when its window has passed meanwhile: refused, and it still waits")
    void approvedTooLate() {
        SalesContract contract = contract();
        contract.submitForCreditReview(order, NOW);

        assertThatThrownBy(() -> contract.approveCredit(order, NOW.plus(java.time.Duration.ofDays(30)), SALES, "x"))
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThat(contract.creditReviewPending()).isTrue();
        assertThat(contract.status()).isEqualTo(SalesContractStatus.DRAFT);
    }

    @Test
    @DisplayName("refused or withdrawn, it is Sales' draft again: edited, then sent")
    void refusedOrWithdrawnGoesBackToSales() {
        SalesContract refused = contract();
        refused.submitForCreditReview(order, NOW);
        refused.refuseCredit();
        assertThat(refused.creditReviewPending()).isFalse();
        refused.revise(order, 1, refused.schedule(), refused.plan());
        refused.send(order, NOW, SALES, "x");
        assertThat(refused.status()).isEqualTo(SalesContractStatus.SENT);

        SalesContract withdrawn = contract();
        withdrawn.submitForCreditReview(order, NOW);
        withdrawn.withdrawCreditReview();
        assertThat(withdrawn.creditReviewPending()).isFalse();
        assertThatThrownBy(withdrawn::withdrawCreditReview)
                .extracting(SalesContractCreditReviewTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_NOT_PENDING);
    }

    @Test
    @DisplayName("cancelled while it waits, it waits no more")
    void cancelledWhileWaiting() {
        SalesContract contract = contract();
        contract.submitForCreditReview(order, NOW);
        contract.cancel("Customer changed plans", NOW, SALES);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.CANCELLED);
        assertThat(contract.creditReviewPending()).isFalse();
    }
}
