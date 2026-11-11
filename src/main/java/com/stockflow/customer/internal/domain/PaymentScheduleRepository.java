package com.stockflow.customer.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Where customers' default payment schedules are kept (port). */
public interface PaymentScheduleRepository {

    Optional<PaymentSchedule> findByCustomerId(UUID customerId);

    PaymentSchedule save(PaymentSchedule schedule);
}
