package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.repository.ProducerSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The producer catalogue (4A-1, kltn-docs 17 §4.3): the Production Coordinator registers producers, edits them, and
 * takes one out of use (INACTIVE) - never deletes it, as production orders name it.
 */
@Service
@Transactional
public class ProducerService {

    private static final Logger log = LoggerFactory.getLogger(ProducerService.class);
    private static final SortWhitelist SORT = SortWhitelist.of("name", "code", "standardLeadDays", "createdAt")
            .withDefault("name", Sort.Direction.ASC);

    private final ProducerRepository producers;
    private final ProducerSearch search;

    ProducerService(ProducerRepository producers, ProducerSearch search) {
        this.producers = producers;
        this.search = search;
    }

    /**
     * @throws BusinessException {@code PRODUCER_CODE_TAKEN}; {@code VALIDATION_FAILED} ({@link Producer#register})
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "producer", resourceId = "#result?.id()")
    public Producer register(String code, Producer.Details details) {
        Producer producer = Producer.register(code, details);
        if (producers.codeTaken(producer.code())) {
            throw codeTaken(producer.code());
        }
        try {
            Producer saved = producers.save(producer);
            log.info("Producer {} registered ({}, {} days)", saved.code(), saved.channel(), saved.standardLeadDays());
            return saved;
        } catch (DataIntegrityViolationException raced) {
            // uk_producer_code: registered by someone else since the check.
            throw codeTaken(producer.code());
        }
    }

    /**
     * New details and status of a producer read at {@code version}.
     *
     * @throws BusinessException {@code PRODUCER_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "producer", resourceId = "#id")
    public Producer update(UUID id, long version, Producer.Details details, Producer.Status status) {
        Producer producer = find(id);
        if (producer.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Producer %s changed since it was read".formatted(producer.code()));
        }
        producer.edit(details);
        if (status != null) {
            producer.changeStatus(status);
        }
        return producers.save(producer);
    }

    @Transactional(readOnly = true)
    public Producer get(UUID id) {
        return find(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<Producer> list(String query, Producer.Status status, int page, int size, String sort) {
        return Pages.toResponse(search.search(query, status, Pages.of(page, size, SORT.parse(sort))));
    }

    private Producer find(UUID id) {
        return producers.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCER_NOT_FOUND,
                "No producer " + id));
    }

    private static BusinessException codeTaken(String code) {
        return new BusinessException(ErrorCode.PRODUCER_CODE_TAKEN, "A producer has code %s already".formatted(code));
    }
}
