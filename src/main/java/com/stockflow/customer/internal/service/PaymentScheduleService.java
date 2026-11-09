package com.stockflow.customer.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.api.PaymentMilestone;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** See {@link PaymentSchedules}. */
@Service
@Transactional
class PaymentScheduleService implements PaymentSchedules {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduleService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

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
                .orElseGet(() -> fromTerms(customerId, creditProfiles.findByCustomerId(customerId)));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-payment-schedule",
            resourceId = "#command.customerId()")
    public DefaultPaymentSchedule save(Save command) {
        lockCustomer(command.customerId());
        Optional<PaymentSchedule> existing = schedules.findByCustomerId(command.customerId());
        long current = existing.map(PaymentSchedule::version).orElse(-1L);
        if (existing.isPresent() && command.expectedVersion() == null) {
            // Someone else set the first schedule meanwhile: the caller has not read it.
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, ("Customer %s already has a default payment "
                    + "schedule: read it and send its version").formatted(command.customerId()));
        }
        if (command.expectedVersion() != null && command.expectedVersion() != current) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The default payment schedule of customer %s "
                    .formatted(command.customerId()) + "changed since it was read");
        }
        Optional<CreditProfile> terms = creditProfiles.findByCustomerId(command.customerId());
        Integer creditDays = terms.map(CreditProfile::creditTermDays).orElse(null);
        List<FieldError> missing = new ArrayList<>();
        List<PaymentSchedule.Installment> lines = new ArrayList<>();
        for (int i = 0; i < command.installments().size(); i++) {
            Line line = command.installments().get(i);
            if (line == null || line.name() == null || line.percent() == null || line.milestone() == null) {
                missing.add(FieldError.of("installments[" + i + "]", "name, percent and milestone are required"));
                continue;
            }
            // 18 §4 step 5: the credit term is the days an installment after delivery gets unless Sales says otherwise.
            int days = line.dueDays() != null ? line.dueDays()
                    : line.milestone() == PaymentMilestone.AFTER_DELIVERY && creditDays != null ? creditDays : 0;
            lines.add(new PaymentSchedule.Installment(line.name(), line.percent(), line.milestone(), days));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid payment schedule", missing);
        }
        boolean credit = terms.map(CreditCheck::approvedCredit).orElse(false);
        var now = clock.instant();
        PaymentSchedule schedule = existing.map(found -> {
            found.replace(lines, command.note(), command.by(), command.approver(), credit, now);
            return found;
        }).orElseGet(() -> PaymentSchedule.create(command.customerId(), lines, command.note(), command.by(),
                command.approver(), credit, now));
        PaymentSchedule saved = schedules.save(schedule);
        log.info("Customer {}: default payment schedule of {} installment(s) set by {}{}", command.customerId(),
                saved.installments().size(), command.by(), !saved.paysAfterDelivery() ? ""
                        : saved.awaitingApproval() ? ", paying after delivery, awaiting accounting"
                        : ", paying after delivery, approved by " + saved.approvedBy());
        return view(saved);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "customer-payment-schedule", resourceId = "#customerId")
    public DefaultPaymentSchedule approve(UUID customerId, long expectedVersion, UUID by) {
        lockCustomer(customerId);
        PaymentSchedule schedule = schedules.findByCustomerId(customerId).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Customer %s has no default payment schedule".formatted(customerId)));
        if (schedule.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The default payment schedule of customer %s "
                    .formatted(customerId) + "changed since it was read");
        }
        boolean credit = creditProfiles.findByCustomerId(customerId).map(CreditCheck::approvedCredit).orElse(false);
        if (!schedule.approve(by, credit, clock.instant())) {
            return view(schedule);
        }
        PaymentSchedule saved = schedules.save(schedule);
        log.info("Customer {}: default payment schedule approved by {}", customerId, by);
        return view(saved);
    }

    /** The customer row lock the credit terms take too: one of the two writers waits for the other (18 BR-03). */
    private void lockCustomer(UUID customerId) {
        customers.findForUpdate(customerId).orElseThrow(() ->
                new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId));
    }

    /**
     * No schedule set: the one the customer's commercial terms give, so a contract drafted for an existing deposit
     * customer asks what they agreed - the deposit share on signing, the rest before delivery - and anyone else pays on
     * signing. Never credit: that is a schedule accounting approves (15 BR-12).
     */
    static DefaultPaymentSchedule fromTerms(UUID customerId, Optional<CreditProfile> terms) {
        BigDecimal deposit = terms.filter(t -> t.allowDeposit() && t.defaultTerm() == CommercialTerm.DEPOSIT)
                .map(CreditProfile::depositPercent).filter(p -> p != null && p.signum() > 0 && p.compareTo(HUNDRED) < 0)
                .map(p -> p.setScale(2, RoundingMode.HALF_UP)).orElse(null);
        List<DefaultPaymentSchedule.Installment> lines = deposit == null
                ? List.of(new DefaultPaymentSchedule.Installment(1, "On signing", HUNDRED, PaymentMilestone.ON_SIGNING,
                        0))
                : List.of(new DefaultPaymentSchedule.Installment(1, "Deposit", deposit, PaymentMilestone.ON_SIGNING, 0),
                        new DefaultPaymentSchedule.Installment(2, "Balance", HUNDRED.subtract(deposit),
                                PaymentMilestone.BEFORE_DELIVERY, 0));
        return new DefaultPaymentSchedule(customerId, false, lines, null, null, null, null, null, -1L);
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
