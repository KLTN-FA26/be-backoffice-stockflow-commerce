package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignProjectQuoteRequestLineJpaEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DesignProjectQuoteRequestLineJpaEntity}. A plain {@link JpaRepository}: the row
 * has a composite key and no version.
 */
public interface DesignProjectQuoteRequestLineJpaRepository extends
        JpaRepository<DesignProjectQuoteRequestLineJpaEntity, DesignProjectQuoteRequestLineJpaEntity.Key> {

    List<DesignProjectQuoteRequestLineJpaEntity> findByRequestIdIn(Collection<java.util.UUID> requestIds);
}
