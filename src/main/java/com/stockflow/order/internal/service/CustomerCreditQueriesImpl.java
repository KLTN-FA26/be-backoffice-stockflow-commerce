package com.stockflow.order.internal.service;

import com.stockflow.order.api.CustomerCredit;
import com.stockflow.order.api.CustomerCreditQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link CustomerCreditQueries} over {@link CreditHoldService#position}, the figure the credit-position screen and the
 * checkout's credit check use, so an answer cannot disagree with what happens to the next order.
 */
@Service
@Transactional(readOnly = true)
class CustomerCreditQueriesImpl implements CustomerCreditQueries {

    private final CreditHoldService credit;

    CustomerCreditQueriesImpl(CreditHoldService credit) {
        this.credit = credit;
    }

    @Override
    public CustomerCredit creditOf(UUID customerId) {
        CreditHoldService.CreditPosition position = credit.position(customerId);
        boolean allowed = position.terms().allowCredit();
        return new CustomerCredit(allowed, allowed ? position.terms().creditLimit() : null, position.exposure(),
                position.available(), position.terms().currency(), position.hasOverdue());
    }
}
