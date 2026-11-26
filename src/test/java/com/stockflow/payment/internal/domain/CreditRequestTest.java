package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The credit request's states and rules (QĐ-A, 2026-11-26), each stated again in ck_credit_request_*. */
class CreditRequestTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PORTAL = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final BigDecimal ASKED = new BigDecimal("500000000");

    private static CreditRequest portal() {
        return CreditRequest.fromPortal(CUSTOMER, ASKED, 30, "  mở rộng  ", PORTAL, NOW);
    }

    private static CreditRequest recommended() {
        CreditRequest request = portal();
        request.recommend("Khách trả đúng hạn", new BigDecimal("400000000"), SALES, NOW);
        return request;
    }

    private static ErrorCode code(Executable call) {
        return assertThrows(BusinessException.class, call).errorCode();
    }

    @Test
    @DisplayName("the customer's request waits for Sales; their opinion hands it to the accountant, who approves")
    void portalToApproved() {
        CreditRequest request = portal();
        assertThat(request.status()).isEqualTo(CreditRequestStatus.SUBMITTED);
        assertThat(request.reason()).isEqualTo("mở rộng");
        assertThat(code(() -> request.approve(ASKED, 30, null, ACCOUNTANT, NOW)))
                .as("nobody decides before Sales give their opinion").isEqualTo(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION);

        request.recommend("Khách trả đúng hạn", null, SALES, NOW);
        assertThat(request.status()).isEqualTo(CreditRequestStatus.PENDING_APPROVAL);
        assertThat(code(() -> request.recommend("again", null, SALES, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION);

        request.approve(new BigDecimal("300000000"), 45, "ok", ACCOUNTANT, NOW);
        assertThat(request.status()).isEqualTo(CreditRequestStatus.APPROVED);
        assertThat(request.approvedLimit()).isEqualByComparingTo("300000000");
        assertThat(request.approvedTermDays()).isEqualTo(45);
        assertThat(request.decidedBy()).isEqualTo(ACCOUNTANT);
        assertThat(code(() -> request.reject("late", ACCOUNTANT, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("Sales' request carries their opinion and waits for the accountant at once")
    void salesRequest() {
        CreditRequest request = CreditRequest.bySales(CUSTOMER, ASKED, 30, null, "Đối tác lâu năm", null, SALES, NOW);
        assertThat(request.status()).isEqualTo(CreditRequestStatus.PENDING_APPROVAL);
        assertThat(request.source()).isEqualTo(CreditRequestSource.SALES);
        assertThat(request.recommendedBy()).isEqualTo(SALES);
        assertThat(code(() -> CreditRequest.bySales(CUSTOMER, ASKED, 30, null, " ", null, SALES, NOW)))
                .as("Sales' opinion is required").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("the accountant grants at most the limit asked (plan §5 q3)")
    void atMostAsked() {
        CreditRequest request = recommended();
        assertThat(code(() -> request.approve(ASKED.add(BigDecimal.ONE), 30, null, ACCOUNTANT, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_ABOVE_REQUESTED);
        request.approve(ASKED, 30, null, ACCOUNTANT, NOW);
        assertThat(request.status()).isEqualTo(CreditRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("four eyes: neither whoever filed it nor whoever gave Sales' opinion decides it")
    void fourEyes() {
        CreditRequest request = recommended();
        assertThat(code(() -> request.approve(ASKED, 30, null, PORTAL, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_SELF_DECISION);
        assertThat(code(() -> request.reject("no", SALES, NOW))).isEqualTo(ErrorCode.CREDIT_REQUEST_SELF_DECISION);
        CreditRequest sales = CreditRequest.bySales(CUSTOMER, ASKED, 30, null, "ok", null, SALES, NOW);
        assertThat(code(() -> sales.approve(ASKED, 30, null, SALES, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_SELF_DECISION);
    }

    @Test
    @DisplayName("a rejection says why")
    void rejectionNeedsAReason() {
        CreditRequest request = recommended();
        assertThat(code(() -> request.reject("  ", ACCOUNTANT, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        request.reject("Đang có nợ quá hạn", ACCOUNTANT, NOW);
        assertThat(request.status()).isEqualTo(CreditRequestStatus.REJECTED);
        assertThat(request.decisionNote()).isEqualTo("Đang có nợ quá hạn");
        assertThat(request.approvedLimit()).isNull();
    }

    @Test
    @DisplayName("withdrawn by whoever filed it, while it is open")
    void withdrawal() {
        CreditRequest mine = portal();
        assertThat(code(() -> mine.withdrawByStaff(SALES, NOW))).as("Sales do not take back the customer's request")
                .isEqualTo(ErrorCode.CREDIT_REQUEST_NOT_FILER);
        mine.withdrawByCustomer(PORTAL, NOW);
        assertThat(mine.status()).isEqualTo(CreditRequestStatus.WITHDRAWN);
        assertThat(code(() -> mine.withdrawByCustomer(PORTAL, NOW)))
                .isEqualTo(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION);

        CreditRequest sales = CreditRequest.bySales(CUSTOMER, ASKED, 30, null, "ok", null, SALES, NOW);
        assertThat(code(() -> sales.withdrawByStaff(UUID.randomUUID(), NOW))).as("another Sales")
                .isEqualTo(ErrorCode.CREDIT_REQUEST_NOT_FILER);
        assertThat(code(() -> sales.withdrawByCustomer(PORTAL, NOW))).isEqualTo(ErrorCode.CREDIT_REQUEST_NOT_FILER);
        sales.withdrawByStaff(SALES, NOW);
        assertThat(sales.status()).isEqualTo(CreditRequestStatus.WITHDRAWN);
        assertThat(sales.decidedBy()).isEqualTo(SALES);
    }

    @Test
    @DisplayName("what is asked: a limit above zero and 0-365 days")
    void whatIsAsked() {
        assertThat(code(() -> CreditRequest.fromPortal(CUSTOMER, BigDecimal.ZERO, 30, null, PORTAL, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> CreditRequest.fromPortal(CUSTOMER, ASKED, 366, null, PORTAL, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> CreditRequest.fromPortal(CUSTOMER, ASKED, -1, null, PORTAL, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> CreditRequest.fromPortal(CUSTOMER, ASKED, 30, "x".repeat(1001), PORTAL, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a stale version is refused")
    void staleVersion() {
        CreditRequest request = portal();
        request.requireVersion(0);
        assertThat(code(() -> request.requireVersion(1))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }
}
