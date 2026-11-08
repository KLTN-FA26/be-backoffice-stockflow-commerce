package com.stockflow.customer.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerPaymentSchedules;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.api.SaveDefaultPaymentScheduleCommand;
import com.stockflow.customer.internal.domain.CreditProfile;
import com.stockflow.customer.internal.domain.CreditProfileRepository;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.PaymentSchedule;
import com.stockflow.customer.internal.domain.PaymentScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** See {@link CustomerPaymentSchedules}. */
@Service
@Transactional
class PaymentScheduleService implements CustomerPaymentSchedules {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduleService.class);

    private final CustomerRepository customers;
    private final CreditProfileRepository creditProfiles;
    private final PaymentScheduleRepository schedules;
    private final Clock clock;

    PaymentScheduleService(CustomerRepository customers, CreditProfileRepository creditProfiles,
                           PaymentScheduleRepository schedules, Clock clock) {
        this.customers = customers;
        this.creditProfiles = creditProfiles;
        this.schedules = schedules;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DefaultPaymentSchedule defaultPaymentSchedule(UUID customerId) {
        if (!customers.existsById(customerId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId);
        }
        return schedules.findByCustomerId(customerId).map(PaymentScheduleService::view)
                .orElseGet(() -> DefaultPaymentSchedule.notConfigured(customerId));
    }

    @Override
    public DefaultPaymentSchedule save(SaveDefaultPaymentScheduleCommand command) {
        customers.findForUpdate(command.customerId()).orElseThrow(() ->
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + command.customerId()));
        var existing = schedules.findByCustomerId(command.customerId());
        long current = existing.map(PaymentSchedule::version).orElse(-1L);
        if (existing.isPresent() && command.expectedVersion() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("expectedVersion is required: customer %s already "
                    + "has a default payment schedule").formatted(command.customerId()),
                    List.of(new FieldError("expectedVersion", "is required when the customer already has a schedule",
                            "NotNull")));
        }
        if (command.expectedVersion() != null && command.expectedVersion() != current) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The default payment schedule of customer %s "
                    .formatted(command.customerId()) + "changed since it was read");
        }
        List<FieldError> missing = new ArrayList<>();
        List<PaymentSchedule.Installment> lines = new ArrayList<>();
        for (int i = 0; i < command.installments().size(); i++) {
            SaveDefaultPaymentScheduleCommand.Line line = command.installments().get(i);
            if (line == null || line.name() == null || line.percent() == null || line.milestone() == null) {
                missing.add(FieldError.of("installments[" + i + "]", "name, percent and milestone are required"));
                continue;
            }
            lines.add(new PaymentSchedule.Installment(line.name(), line.percent(), line.milestone(),
                    line.dueDays() == null ? 0 : line.dueDays()));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid payment schedule", missing);
        }
        boolean credit = hasCredit(command.customerId());
        var now = clock.instant();
        PaymentSchedule schedule = existing.map(found -> {
            found.replace(lines, command.note(), command.by(), command.approver(), credit, now);
            return found;
        }).orElseGet(() -> PaymentSchedule.create(command.customerId(), lines, command.note(), command.by(),
                command.approver(), credit, now));
        PaymentSchedule saved = schedules.save(schedule);
        log.info("Customer {}: default payment schedule of {} instalment(s){} set by {}", command.customerId(),
                saved.installments().size(), saved.paysAfterDelivery() ? ", paying after delivery," : "",
                command.by());
        return view(saved);
    }

    /** Credit approved by accounting with a limit above 0 (18 BR-03). */
    private boolean hasCredit(UUID customerId) {
        return creditProfiles.findByCustomerId(customerId).filter(CreditProfile::allowCredit)
                .map(CreditProfile::creditLimit).filter(Objects::nonNull)
                .map(limit -> limit.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    static DefaultPaymentSchedule view(PaymentSchedule s) {
        List<DefaultPaymentSchedule.Installment> lines = new ArrayList<>();
        for (int i = 0; i < s.installments().size(); i++) {
            PaymentSchedule.Installment line = s.installments().get(i);
            lines.add(new DefaultPaymentSchedule.Installment(i + 1, line.name(), line.percent(), line.milestone(),
                    line.dueDays()));
        }
        return new DefaultPaymentSchedule(s.customerId(), true, lines, s.setBy(), s.setAt(), s.approvedBy(),
                s.approvedAt(), s.note(), s.version());
    }
}
