package com.stockflow.payment.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.SettlementBook;
import com.stockflow.payment.internal.repository.CustomerLedgerQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment confirmation of a fee item names what the item is (audit r3 LM-3, after the merge of round 3): a closure of
 * the rest may carry goods money still owed by a batch it closed whole onto its fee item
 * ({@code order_settlement.carried_to_fee_amount}). The debt reconciliation already splits such an item into the goods'
 * price and the fee; the confirmation of its payment used to call it all "Phí hủy đơn hàng".
 */
class PaymentConfirmationCarriedLabelTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final ResourceBundle T = DocumentParts.bundle();

    private final InstallmentService installments = mock(InstallmentService.class);
    private final CustomerLedgerQuery ledger = mock(CustomerLedgerQuery.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CustomerLegalProfiles legalProfiles = mock(CustomerLegalProfiles.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final InstallmentBook book = mock(InstallmentBook.class);
    private final PaymentConfirmationDocument paper = mock(PaymentConfirmationDocument.class);
    private final PaymentPaperService papers = new PaymentPaperService(installments, book, mock(SettlementBook.class),
            ledger, customers, legalProfiles, identities, paper, mock(RefundConfirmationDocument.class),
            mock(DebtReconciliationDocument.class), mock(InstallmentOrders.class),
            Clock.fixed(Instant.parse("2026-11-30T03:00:00Z"), ZoneOffset.UTC));

    {
        CustomerSummary customer = mock(CustomerSummary.class);
        when(customer.fullName()).thenReturn("Quán cà phê Mộc");
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(legalProfiles.legalProfile(CUSTOMER)).thenReturn(Optional.empty());
        when(identities.displayNames(any())).thenReturn(Map.of());
        when(book.findReceipt(any())).thenReturn(Optional.empty());
        when(paper.render(any())).thenReturn(new byte[0]);
    }

    /** The label the confirmation of a payment of a fee item of {@code owed}, {@code carried} of it goods money, gets. */
    private String labelOf(BigDecimal owed, BigDecimal carried) {
        Installment plan = Installment.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CUSTOMER,
                "HD-1", "SO-1", 1, "Trước đợt 1", InstallmentMilestone.BEFORE_DELIVERY, null, null,
                new BigDecimal("255000"), "VND", null);
        Installment fee = Installment.openFee(plan, Installment.FIRST_FEE_SEQ, "Phí", owed,
                Instant.parse("2026-11-20T03:00:00Z"), LocalDate.of(2026, 11, 20));
        UUID receipt = UUID.randomUUID();
        when(installments.detail(fee.id(), null)).thenReturn(new InstallmentService.InstallmentDetail(fee, List.of(
                new InstallmentBook.ReceiptOfInstallment(receipt, "FT1", LocalDate.of(2026, 11, 21), owed,
                        PaymentReceiptStatus.RECORDED, Instant.parse("2026-11-21T03:00:00Z")))));
        when(ledger.carriedOnto(fee.id())).thenReturn(carried);

        papers.paymentConfirmation(fee.id(), receipt, null);
        ArgumentCaptor<PaymentConfirmationDocument.Facts> facts =
                ArgumentCaptor.forClass(PaymentConfirmationDocument.Facts.class);
        verify(paper, org.mockito.Mockito.atLeastOnce()).render(facts.capture());
        assertThat(facts.getValue().contractValue()).as("a fee item is no part of the contract's value").isNull();
        return facts.getValue().installment();
    }

    @Test
    @DisplayName("a fee item that is all goods money carried by a closure of the rest reads as that money, not as a fee")
    void allCarried() {
        assertThat(labelOf(new BigDecimal("35000"), new BigDecimal("35000")))
                .isEqualTo(T.getString("payment-papers.fee.carried"))
                .doesNotContain(T.getString("payment-papers.fee.CANCELLATION_FEE"));
    }

    @Test
    @DisplayName("a fee item carrying goods money beside an agreed fee names both parts and their amounts")
    void carriedBesideAFee() {
        assertThat(labelOf(new BigDecimal("135000"), new BigDecimal("35000")))
                .isEqualTo(T.getString("payment-papers.fee.carriedWithFee").formatted(
                        DocumentFormats.money(new BigDecimal("35000")),
                        T.getString("payment-papers.fee.CANCELLATION_FEE").toLowerCase(Locale.forLanguageTag("vi")),
                        DocumentFormats.money(new BigDecimal("100000"))));
    }

    @Test
    @DisplayName("a fee item carrying nothing keeps its fee label")
    void nothingCarried() {
        assertThat(labelOf(new BigDecimal("100000"), BigDecimal.ZERO))
                .isEqualTo(T.getString("payment-papers.fee.CANCELLATION_FEE"));
    }
}
