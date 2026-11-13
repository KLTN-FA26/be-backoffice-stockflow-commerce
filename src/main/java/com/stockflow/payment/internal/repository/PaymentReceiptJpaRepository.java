package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.PaymentReceiptJpaEntity;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;

interface PaymentReceiptJpaRepository extends BaseJpaRepository<PaymentReceiptJpaEntity> {

    /** By the normalised reference (15 BR-03): {@code PaymentReceipt.normalize}, as the unique index compares. */
    boolean existsByReferenceNormalizedAndStatus(String referenceNormalized, PaymentReceiptStatus status);
}
