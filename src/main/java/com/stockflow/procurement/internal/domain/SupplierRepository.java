package com.stockflow.procurement.internal.domain;
import com.stockflow.common.api.PageResponse;
import java.util.Optional;
import java.util.UUID;
/** Persistence port: no JPA rows or Specifications escape the adapter. */
public interface SupplierRepository {
    Optional<Supplier> findById(UUID id);
    Optional<Supplier> findByIdForUpdate(UUID id);
    Supplier save(Supplier supplier);
    boolean hasOpenOrders(UUID id);
    boolean codeExists(String code, UUID excludingId);
    boolean taxCodeExists(String taxCode, UUID excludingId);
    PageResponse<Supplier> list(int page, int size, String search, SupplierStatus status, String sort);
    SupplierMetrics performance(UUID id);
}
