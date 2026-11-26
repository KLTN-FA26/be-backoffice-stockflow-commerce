package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import com.stockflow.payment.internal.entity.BankTransactionJpaEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BankTransactionJpaRepository extends BaseJpaRepository<BankTransactionJpaEntity> {

    Optional<BankTransactionJpaEntity> findByProviderAndProviderTransactionId(String provider,
                                                                              String providerTransactionId);

    Optional<BankTransactionJpaEntity> findByProviderAndDedupeKey(String provider, String dedupeKey);

    Optional<BankTransactionJpaEntity> findByReceiptId(UUID receiptId);

    List<BankTransactionJpaEntity> findByStatusAndReceivedAtBeforeOrderByReceivedAtAsc(BankTransactionStatus status,
                                                                                      Instant before);
}
