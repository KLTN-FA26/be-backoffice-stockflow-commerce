package com.stockflow.order.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Specs;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.entity.ProducerJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@link ProducerRepository} and the catalogue's paged search over {@code ordering.producer}. */
@Repository
class ProducerRepositoryAdapter implements ProducerRepository, ProducerSearch {

    private final ProducerJpaRepository jpa;

    ProducerRepositoryAdapter(ProducerJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Producer> findById(UUID id) {
        return jpa.findById(id).map(ProducerRepositoryAdapter::toDomain);
    }

    @Override
    public boolean codeTaken(String code) {
        return jpa.existsByCode(code);
    }

    @Override
    public Producer save(Producer p) {
        ProducerJpaEntity entity = jpa.findById(p.id()).orElse(null);
        if (entity == null) {
            entity = new ProducerJpaEntity(p.id(), p.code());
        } else if (entity.getVersion() != p.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Producer %s changed since it was read".formatted(p.code()));
        }
        entity.apply(p.name(), p.contactName(), p.contactPhone(), p.email(), p.channel(), p.standardLeadDays(),
                p.status(), p.note());
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public Page<Producer> search(String query, Producer.Status status, Pageable pageable) {
        String like = query == null || query.isBlank() ? null
                : "%" + query.strip().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
        Specification<ProducerJpaEntity> spec = Specification.<ProducerJpaEntity>where(Specs.eq("status", status))
                .and(like == null ? null : (root, q, cb) -> cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("name")), like, '\\')));
        return jpa.findAll(spec, pageable).map(ProducerRepositoryAdapter::toDomain);
    }

    private static Producer toDomain(ProducerJpaEntity e) {
        return new Producer(e.getId(), e.getCode(), e.getName(), e.getContactName(), e.getContactPhone(), e.getEmail(),
                e.getChannel(), e.getStandardLeadDays(), e.getStatus(), e.getNote(), e.getVersion());
    }
}
