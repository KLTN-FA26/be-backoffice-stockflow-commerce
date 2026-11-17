package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ProducerJpaEntity;

interface ProducerJpaRepository extends BaseJpaRepository<ProducerJpaEntity> {

    boolean existsByCode(String code);
}
