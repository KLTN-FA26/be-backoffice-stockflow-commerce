package com.stockflow.order.internal.repository;
import com.stockflow.common.security.ScopedJpaRepository;
import com.stockflow.order.internal.entity.DesignQuoteJpaEntity;
import java.util.*;
public interface DesignQuoteJpaRepository extends ScopedJpaRepository<DesignQuoteJpaEntity> {
    @Override default DesignQuoteJpaEntity scopePrototype(){return DesignQuoteJpaEntity.SCOPE_PROTOTYPE;}
    Optional<DesignQuoteJpaEntity> findByRequestId(UUID requestId);
}
