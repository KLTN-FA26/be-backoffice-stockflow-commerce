package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SupplierPerformanceSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class SupplierServiceImpl implements SupplierService {
    private final SupplierRepository suppliers;
    private final Clock clock;
    private final NotificationService notifications;

    SupplierServiceImpl(SupplierRepository suppliers, Clock clock, NotificationService notifications) {
        this.suppliers = suppliers;
        this.clock = clock;
        this.notifications = notifications;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "supplier")
    public SupplierSummary create(SaveSupplierCommand command) {
        var supplier = Supplier.create(details(command));
        checkUnique(supplier, null);
        validateDelivery(supplier);
        return summary(suppliers.save(supplier));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier", resourceId = "#id")
    public SupplierSummary update(UUID id, SaveSupplierCommand command) {
        var supplier = load(id, true);
        supplier.update(details(command), suppliers.hasOpenOrders(id));
        checkUnique(supplier, id);
        validateDelivery(supplier);
        return summary(suppliers.save(supplier));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "supplier", resourceId = "#id")
    public void deactivate(UUID id) {
        var supplier = load(id, true);
        supplier.deactivate(suppliers.hasOpenOrders(id));
        suppliers.save(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierSummary> findById(UUID id) {
        return suppliers.findById(id).map(SupplierServiceImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierSummary> list(int page, int size, String search, String status, String sort) {
        var requestedStatus = status == null || status.isBlank() ? null : SupplierStatus.valueOf(status);
        return suppliers.list(page, size, search, requestedStatus, sort).map(SupplierServiceImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierPerformanceSummary performance(UUID id) {
        load(id, false);
        var p = suppliers.performance(id);
        return new SupplierPerformanceSummary(id, p.totalPurchaseOrders(), p.fulfilledPurchaseOrders(),
                p.onTimeOrders(), p.lateOrders(), percent(p.onTimeOrders(), p.onTimeOrders() + p.lateOrders()),
                p.averageLeadTimeDays() == null ? null : p.averageLeadTimeDays().setScale(2, RoundingMode.HALF_UP),
                p.acceptedQuantity(), p.rejectedQuantity(),
                percent(p.acceptedQuantity(), p.acceptedQuantity() + p.rejectedQuantity()), clock.instant());
    }

    private Supplier load(UUID id, boolean locked) {
        return (locked ? suppliers.findByIdForUpdate(id) : suppliers.findById(id)).orElseThrow(() ->
                new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + id));
    }

    private void checkUnique(Supplier supplier, UUID excludingId) {
        var d = supplier.details();
        if (suppliers.codeExists(d.code(), excludingId)) throw new BusinessException(ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS);
        if (suppliers.taxCodeExists(d.taxCode(), excludingId)) throw new BusinessException(ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS);
    }

    private void validateDelivery(Supplier supplier) {
        var d = supplier.details();
        notifications.validateSupplierDelivery(d.communicationChannel().name(),
                d.communicationChannel() == SupplierCommunicationChannel.EMAIL ? d.email() : d.apiEndpoint());
    }

    private static SupplierDetails details(SaveSupplierCommand c) {
        return new SupplierDetails(c.code(), c.name(), c.contactName(), c.email(), c.phone(), c.taxCode(),
                SupplierStatus.valueOf(c.status()), c.paymentTermDays(), c.leadTimeDays(),
                SupplierCommunicationChannel.valueOf(c.communicationChannel()), c.apiEndpoint());
    }

    private static BigDecimal percent(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static SupplierSummary summary(Supplier supplier) {
        var d = supplier.details();
        return new SupplierSummary(supplier.id(), d.code(), d.name(), d.contactName(), d.email(), d.phone(),
                d.taxCode(), d.status().name(), d.paymentTermDays(), d.leadTimeDays(), d.communicationChannel().name(),
                d.apiEndpoint(), supplier.createdAt(), supplier.lastModifiedAt());
    }
}
