package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** BALCREDIT: which balance policy a deposit order is placed on (G2-3 rules plus ON_CREDIT for a customer on credit). */
class BalancePolicyForTest {

    private static CreditTerms terms(boolean credit, DepositBalancePolicy policy) {
        return new CreditTerms(UUID.randomUUID(), true, true, true, credit, CommercialTerm.DEPOSIT,
                new BigDecimal("30"), credit ? new BigDecimal("10000000") : null, credit ? 30 : null, "VND", null, null,
                null, 0L, policy);
    }

    @Test
    @DisplayName("ON_CREDIT: the customer's own policy, or Sales' choice, for a customer allowed credit only")
    void onCreditNeedsCredit() {
        assertThat(OrderServiceImpl.balancePolicyFor(PaymentTerm.DEPOSIT, null,
                terms(true, DepositBalancePolicy.ON_CREDIT))).isEqualTo(BalancePolicy.ON_CREDIT);
        assertThat(OrderServiceImpl.balancePolicyFor(PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT,
                terms(true, DepositBalancePolicy.BEFORE_DELIVERY))).isEqualTo(BalancePolicy.ON_CREDIT);
        assertThat(catchThrowableOfType(BusinessException.class, () -> OrderServiceImpl.balancePolicyFor(
                PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT, terms(false, DepositBalancePolicy.BEFORE_DELIVERY)))
                .errorCode()).isEqualTo(ErrorCode.BALANCE_POLICY_NOT_ALLOWED);
    }

    @Test
    @DisplayName("tightening to BEFORE_DELIVERY stays allowed; ON_DELIVERY for an ON_CREDIT customer is not")
    void otherPolicies() {
        assertThat(OrderServiceImpl.balancePolicyFor(PaymentTerm.DEPOSIT, BalancePolicy.BEFORE_DELIVERY,
                terms(true, DepositBalancePolicy.ON_CREDIT))).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        assertThat(catchThrowableOfType(BusinessException.class, () -> OrderServiceImpl.balancePolicyFor(
                PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY, terms(true, DepositBalancePolicy.ON_CREDIT)))
                .errorCode()).isEqualTo(ErrorCode.BALANCE_POLICY_NOT_ALLOWED);
        assertThat(OrderServiceImpl.balancePolicyFor(PaymentTerm.CREDIT, null,
                terms(true, DepositBalancePolicy.ON_CREDIT))).isNull();
    }

    @Test
    @DisplayName("SCRUM-193: allowedBalancePolicies is exactly what balancePolicyFor accepts from the customer")
    void allowedListMatchesWhatIsAccepted() {
        for (boolean credit : new boolean[] {false, true}) {
            for (DepositBalancePolicy own : credit ? DepositBalancePolicy.values()
                    : new DepositBalancePolicy[] {DepositBalancePolicy.BEFORE_DELIVERY, DepositBalancePolicy.ON_DELIVERY}) {
                CreditTerms terms = terms(credit, own);
                var allowed = OrderServiceImpl.allowedBalancePolicies(terms);
                for (BalancePolicy asked : BalancePolicy.values()) {
                    boolean accepted;
                    try {
                        OrderServiceImpl.balancePolicyFor(PaymentTerm.DEPOSIT, asked, terms);
                        accepted = true;
                    } catch (BusinessException refused) {
                        assertThat(refused.errorCode()).isEqualTo(ErrorCode.BALANCE_POLICY_NOT_ALLOWED);
                        accepted = false;
                    }
                    assertThat(allowed.contains(asked)).as("%s for credit=%s own=%s", asked, credit, own)
                            .isEqualTo(accepted);
                }
            }
        }
    }

    @Test
    @DisplayName("SCRUM-193: the list in the order paid first, then own policy, then credit; empty without deposits")
    void allowedListShape() {
        assertThat(OrderServiceImpl.allowedBalancePolicies(terms(true, DepositBalancePolicy.ON_CREDIT)))
                .containsExactly(BalancePolicy.BEFORE_DELIVERY, BalancePolicy.ON_CREDIT);
        assertThat(OrderServiceImpl.allowedBalancePolicies(terms(false, DepositBalancePolicy.ON_DELIVERY)))
                .containsExactly(BalancePolicy.BEFORE_DELIVERY, BalancePolicy.ON_DELIVERY);
        assertThat(OrderServiceImpl.allowedBalancePolicies(CreditTerms.prepaidOnly(UUID.randomUUID()))).isEmpty();
    }
}
