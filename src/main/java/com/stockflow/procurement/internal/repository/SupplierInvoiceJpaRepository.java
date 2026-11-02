package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.SupplierInvoiceJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface SupplierInvoiceJpaRepository extends BaseJpaRepository<SupplierInvoiceJpaEntity> {

    /** Lines fetched with the header; the variances are a second, small query when read. */
    @Query("select distinct i from SupplierInvoiceJpaEntity i left join fetch i.lines where i.id = :id")
    Optional<SupplierInvoiceJpaEntity> findByIdWithLines(@Param("id") UUID id);

    @Query("""
            select count(i) > 0 from SupplierInvoiceJpaEntity i
             where i.supplierId = :supplier and lower(i.invoiceNumber) = lower(:number) and i.id <> :excluding""")
    boolean numberTaken(@Param("supplier") UUID supplierId, @Param("number") String invoiceNumber,
                        @Param("excluding") UUID excluding);
}
