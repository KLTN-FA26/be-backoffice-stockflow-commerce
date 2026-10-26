package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface StockPackageJpaRepository extends JpaRepository<StockPackageJpaEntity, UUID> {

    Optional<StockPackageJpaEntity> findByLpn(String lpn);

    java.util.List<StockPackageJpaEntity> findByLpnIn(java.util.Collection<String> lpns);

    java.util.List<StockPackageJpaEntity> findByGoodsReceiptLineIdIn(java.util.Collection<UUID> goodsReceiptLineIds);
}
