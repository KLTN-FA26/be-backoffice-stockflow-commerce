package com.stockflow.payment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Review of WF-05, F9: no installment is in OVERDUE since overdue became a flag worked out from the due day, so a
 * filter on that status would answer an empty list - which a client written against the old status reads as
 * "nothing overdue". It is refused with a 400 naming the flag instead, before anything is searched.
 */
class InstallmentOverdueFilterTest {

    private final InstallmentSearch search = mock(InstallmentSearch.class);
    private final InstallmentService service = new InstallmentService(mock(InstallmentBook.class), search,
            mock(InstallmentOrders.class), new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("F9: status=OVERDUE is refused with 400 INSTALLMENT_OVERDUE_IS_A_FLAG, nothing searched")
    void overdueIsNotAStatusToFilterOn() {
        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> service.installments(null,
                null, InstallmentStatus.OVERDUE, null, null, null, null, 0, 20, null));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSTALLMENT_OVERDUE_IS_A_FLAG);
        assertThat(refused.errorCode().httpStatus()).isEqualTo(400);
        verifyNoInteractions(search);
    }
}
