package com.stockflow.inventory.internal.repository;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import com.stockflow.common.security.ScopedJpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface CycleCountJpaRepository extends ScopedJpaRepository<CycleCountJpaEntity> {
    @Override default CycleCountJpaEntity scopePrototype(){return CycleCountJpaEntity.SCOPE_PROTOTYPE;}
    Optional<CycleCountJpaEntity> findByRequestId(UUID requestId);
}
