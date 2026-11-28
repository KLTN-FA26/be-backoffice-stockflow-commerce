package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.order.internal.repository.OrderOnBehalfActions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The order module's answer to {@link SalesAudiences}: {@code customer :: api}'s {@link ResponsibleSales}, the one rule
 * of who looks after a customer, for a customer, an order's customer, or a portal login's customer. Read-only, a few
 * statements per question; the notice is written from the answer by notification.
 */
@Service
@Transactional(readOnly = true)
class SalesAudienceDirectory implements SalesAudiences {

    private final ResponsibleSales responsible;
    private final OrderOnBehalfActions orders;
    private final CustomerService customers;
    private final Clock clock;

    SalesAudienceDirectory(ResponsibleSales responsible, OrderOnBehalfActions orders, CustomerService customers,
                           Clock clock) {
        this.responsible = responsible;
        this.orders = orders;
        this.customers = customers;
        this.clock = clock;
    }

    @Override
    public Optional<SalesAudience> ofCustomer(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return responsible.responsibleFor(customerId, BusinessCalendar.date(clock.instant()))
                .map(SalesAudienceDirectory::audience);
    }

    @Override
    public Optional<SalesAudience> ofOrder(UUID orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orders.orderFacts(orderId).flatMap(order -> ofCustomer(order.customerId()));
    }

    @Override
    public Optional<SalesAudience> ofCustomerAccount(UUID portalUserId) {
        if (portalUserId == null) {
            return Optional.empty();
        }
        return customers.findByUserId(portalUserId).flatMap(customer -> ofCustomer(customer.customerId()));
    }

    static SalesAudience audience(Responsible who) {
        return new SalesAudience(who.recipients(), who.needsAssignment());
    }
}
