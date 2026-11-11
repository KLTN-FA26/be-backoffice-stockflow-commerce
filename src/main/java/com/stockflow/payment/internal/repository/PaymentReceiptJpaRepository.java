package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.PaymentReceiptJpaEntity;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;

interface PaymentReceiptJpaRepository extends BaseJpaRepository<PaymentReceiptJpaEntity> {

    boolean existsByReferenceAndStatus(String reference, PaymentReceiptStatus status);
}
