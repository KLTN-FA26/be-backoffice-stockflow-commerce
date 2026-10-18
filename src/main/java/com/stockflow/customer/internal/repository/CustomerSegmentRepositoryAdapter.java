package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.CustomerSegment;
import com.stockflow.customer.internal.domain.CustomerSegmentRepository;
import com.stockflow.customer.internal.entity.SegmentJpaEntity;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link CustomerSegmentRepository} on {@link SegmentJpaEntity}; the version is JPA's, checked by the aggregate first. */
@Repository
class CustomerSegmentRepositoryAdapter implements CustomerSegmentRepository {

    private final SegmentJpaRepository segments;
    private final CustomerJpaRepository customers;

    CustomerSegmentRepositoryAdapter(SegmentJpaRepository segments, CustomerJpaRepository customers) {
        this.segments = segments;
        this.customers = customers;
    }

    @Override
    public Optional<CustomerSegment> find(UUID id) {
        return segments.findById(id).map(CustomerSegmentRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<CustomerSegment> findForUpdate(UUID id) {
        return segments.lockById(id).map(CustomerSegmentRepositoryAdapter::toDomain);
    }

    @Override
    public boolean codeTaken(String code) {
        return segments.existsByCode(code);
    }

    @Override
    public List<CustomerSegment> all() {
        return segments.findAll(Sort.by("code")).stream().map(CustomerSegmentRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Map<UUID, Long> customerCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : customers.countBySegment()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    public CustomerSegment save(CustomerSegment segment) {
        SegmentJpaEntity row = segments.findById(segment.id())
                .map(existing -> {
                    existing.apply(segment.name(), segment.description());
                    return existing;
                })
                .orElseGet(() -> new SegmentJpaEntity(segment.id(), segment.code(), segment.name(),
                        segment.description()));
        return toDomain(segments.saveAndFlush(row));
    }

    private static CustomerSegment toDomain(SegmentJpaEntity row) {
        return CustomerSegment.restore(row.getId(), row.getCode(), row.getName(), row.getDescription(),
                row.getVersion());
    }
}
