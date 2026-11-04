package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** SCRUM-193: the company account customers transfer to - all three parts or none, never half an account. */
class CompanyBankAccountTest {

    private static PaymentServiceImpl service(String bank, String number, String holder) {
        return new PaymentServiceImpl(mock(ReceivableBook.class), new CompanyBankProperties(bank, number, holder));
    }

    @Test
    @DisplayName("all three set: the account, trimmed")
    void configured() {
        assertThat(service(" Vietcombank ", "0071000888999 ", " CONG TY STOCKFLOW").companyBankAccount())
                .contains(new CompanyBankAccount("Vietcombank", "0071000888999", "CONG TY STOCKFLOW"));
    }

    @Test
    @DisplayName("any part missing or blank: no account at all")
    void notConfigured() {
        assertThat(service("", "", "").companyBankAccount()).isEmpty();
        assertThat(service(null, null, null).companyBankAccount()).isEmpty();
        assertThat(service("Vietcombank", "0071000888999", "  ").companyBankAccount()).isEmpty();
        assertThat(service("Vietcombank", null, "CONG TY STOCKFLOW").companyBankAccount()).isEmpty();
        assertThat(service(" ", "0071000888999", "CONG TY STOCKFLOW").companyBankAccount()).isEmpty();
    }
}
