package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.CustomerSegment;
import com.stockflow.customer.internal.domain.CustomerSegmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer groups from the back office (user decision P7, 2026-10-12): the E-commerce Admin (and System Admin)
 * creates and renames groups and puts customers in them; Sales and the Order Coordinator read. Who may call
 * what is the {@code customer-segments} resource (V20261014000600).
 *
 * <p>A group change moves only later prices: catalog reads a customer's group when it prices (own tiers, then
 * the group's price, then the base price), and quotes and orders keep the prices they were given.</p>
 */
@Service
@Transactional
public class CustomerSegmentService {

    private final CustomerSegmentRepository segments;
    private final CustomerRepository customers;
    private final CustomerService customerService;

    CustomerSegmentService(CustomerSegmentRepository segments, CustomerRepository customers,
                           CustomerService customerService) {
        this.segments = segments;
        this.customers = customers;
        this.customerService = customerService;
    }

    /** @param customerCount customers in the group now */
    public record SegmentView(UUID id, String code, String name, String description, long customerCount,
                              long version) {
    }

    @Transactional(readOnly = true)
    public List<SegmentView> list() {
        Map<UUID, Long> counts = segments.customerCounts();
        return segments.all().stream().map(s -> view(s, counts.getOrDefault(s.id(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public SegmentView get(UUID segmentId) {
        CustomerSegment segment = segments.find(segmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEGMENT_NOT_FOUND));
        return view(segment, segments.customerCounts().getOrDefault(segmentId, 0L));
    }

    /** @throws BusinessException {@code CONFLICT} for a code in use, {@code VALIDATION_FAILED} */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer-segment", resourceId = "#result?.id()")
    public SegmentView create(String code, String name, String description) {
        CustomerSegment segment = CustomerSegment.create(Identifiers.newId(), code, name, description);
        if (segments.codeTaken(segment.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "A customer group " + segment.code() + " already exists");
        }
        try {
            return view(segments.save(segment), 0L);
        } catch (DataIntegrityViolationException raced) {
            throw new BusinessException(ErrorCode.CONFLICT, "A customer group " + segment.code() + " already exists");
        }
    }

    /** @throws BusinessException {@code SEGMENT_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED} */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-segment", resourceId = "#segmentId")
    public SegmentView update(UUID segmentId, String name, String description, long expectedVersion) {
        CustomerSegment segment = segments.findForUpdate(segmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEGMENT_NOT_FOUND));
        segment.rename(name, description, expectedVersion);
        return view(segments.save(segment), segments.customerCounts().getOrDefault(segmentId, 0L));
    }

    /**
     * Puts the customer in a group, or in none when {@code segmentId} is null.
     *
     * @param expectedVersion the customer's version as read: two admins moving one customer at once cannot
     *                        both win unseen
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}, {@code SEGMENT_NOT_FOUND}, {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-segment-assignment", resourceId = "#customerId")
    public CustomerSummary assign(UUID customerId, UUID segmentId, long expectedVersion) {
        Customer customer = customers.findForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (segmentId != null && segments.find(segmentId).isEmpty()) {
            throw new BusinessException(ErrorCode.SEGMENT_NOT_FOUND);
        }
        try {
            customer.assignSegment(segmentId, expectedVersion);
        } catch (IllegalStateException stale) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, stale.getMessage());
        }
        customers.save(customer);
        return customerService.findById(customerId).orElseThrow();
    }

    private static SegmentView view(CustomerSegment segment, long customers) {
        return new SegmentView(segment.id(), segment.code(), segment.name(), segment.description(), customers,
                segment.version());
    }
}
