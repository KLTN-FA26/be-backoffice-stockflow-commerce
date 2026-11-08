package com.stockflow.design.internal.service;

import com.stockflow.common.persistence.Pages;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Sales Manager's side of a customer's design (kltn-docs 12 §4.4): the queue of designs waiting for review. Who may
 * read it is decided by the permission on the endpoint ({@code design-designs:APPROVE}), not by who owns the designs.
 */
@Service
@Transactional(readOnly = true)
public class DesignReviewService {

    private final DesignDraftJpaRepository drafts;
    private final DraftViews views;

    public DesignReviewService(DesignDraftJpaRepository drafts, DraftViews views) {
        this.drafts = drafts;
        this.views = views;
    }

    /** Customers' designs waiting for review, the longest waiting first. */
    public Page<DesignWorkflowService.DraftView> queue(Pageable page) {
        Page<DesignDraftJpaEntity> found = drafts.reviewQueue(Pages.of(page.getPageNumber(), page.getPageSize()));
        return new PageImpl<>(views.of(found.getContent()), found.getPageable(), found.getTotalElements());
    }
}
