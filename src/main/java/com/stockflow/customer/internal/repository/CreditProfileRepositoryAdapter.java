package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.CreditProfile;
import com.stockflow.customer.internal.domain.CreditProfileRepository;
import com.stockflow.customer.internal.entity.CreditProfileJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class CreditProfileRepositoryAdapter implements CreditProfileRepository {

    private final CreditProfileJpaRepository jpa;

    CreditProfileRepositoryAdapter(CreditProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<CreditProfile> findByCustomerId(UUID customerId) {
        return jpa.findByCustomerId(customerId).map(CreditProfileRepositoryAdapter::toDomain);
    }

    @Override
    public CreditProfile save(CreditProfile profile) {
        CreditProfileJpaEntity entity = jpa.findById(profile.id())
                .orElseGet(() -> new CreditProfileJpaEntity(profile.id(), profile.customerId(), profile.currency()));
        entity.apply(profile.allowPrepaid(), profile.allowDeposit(), profile.allowCredit(), profile.defaultTerm(),
                profile.depositPercent(), profile.creditLimit(), profile.creditTermDays(), profile.approvedBy(),
                profile.approvedAt(), profile.note());
        return toDomain(jpa.saveAndFlush(entity));
    }

    private static CreditProfile toDomain(CreditProfileJpaEntity e) {
        return new CreditProfile(e.getId(), e.getCustomerId(), e.isAllowPrepaid(), e.isAllowDeposit(), e.isAllowCredit(),
                e.getDefaultPaymentTerm(), e.getDepositPercent(), e.getCreditLimit(), e.getCreditTermDays(),
                e.getCurrency(), e.getApprovedBy(), e.getApprovedAt(), e.getNote(), e.getVersion());
    }
}
