package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.PaymentSchedule;
import com.stockflow.customer.internal.domain.PaymentScheduleRepository;
import com.stockflow.customer.internal.entity.PaymentScheduleJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentScheduleRepositoryAdapter implements PaymentScheduleRepository {

    private final PaymentScheduleJpaRepository jpa;

    PaymentScheduleRepositoryAdapter(PaymentScheduleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<PaymentSchedule> findByCustomerId(UUID customerId) {
        return jpa.findByCustomerId(customerId).map(PaymentScheduleRepositoryAdapter::toDomain);
    }

    @Override
    public PaymentSchedule save(PaymentSchedule schedule) {
        PaymentScheduleJpaEntity entity = jpa.findById(schedule.id())
                .orElseGet(() -> new PaymentScheduleJpaEntity(schedule.id(), schedule.customerId()));
        List<PaymentScheduleJpaEntity.InstallmentRow> rows = new ArrayList<>();
        for (int i = 0; i < schedule.installments().size(); i++) {
            PaymentSchedule.Installment line = schedule.installments().get(i);
            rows.add(new PaymentScheduleJpaEntity.InstallmentRow(i + 1, line.name(), line.percent(), line.milestone(),
                    line.dueDays()));
        }
        entity.apply(rows, schedule.setBy(), schedule.setAt(), schedule.approvedBy(), schedule.approvedAt(),
                schedule.note());
        return toDomain(jpa.saveAndFlush(entity));
    }

    private static PaymentSchedule toDomain(PaymentScheduleJpaEntity e) {
        return new PaymentSchedule(e.getId(), e.getCustomerId(), e.getInstallments().stream()
                .map(r -> new PaymentSchedule.Installment(r.getName(), r.getPercent(), r.getMilestone(),
                        r.getDueDays())).toList(),
                e.getSetBy(), e.getSetAt(), e.getApprovedBy(), e.getApprovedAt(), e.getNote(), e.getVersion());
    }
}
