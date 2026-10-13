package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.notification.internal.repository.UserNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class UserInboxImpl implements UserInbox {

    private static final Logger log = LoggerFactory.getLogger(UserInboxImpl.class);

    private final UserNotificationRepository repository;
    private final Clock clock;
    private final int retentionDays;

    UserInboxImpl(UserNotificationRepository repository, Clock clock,
                  @Value("${stockflow.notification.inbox-retention-days:90}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("stockflow.notification.inbox-retention-days must be at least 1");
        }
        this.repository = repository;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Override
    @Transactional(readOnly = true)
    public InboxPage list(UUID userId, boolean unreadOnly, int page, int size) {
        Pageable pageable = Pages.of(page, size, Sort.unsorted());
        List<Item> items = repository.page(userId, unreadOnly, pageable.getPageSize(), pageable.getOffset()).stream()
                .map(r -> new Item(r.id(), r.category(), r.severity(), r.title(), r.body(), r.link(),
                        r.sourceType(), r.sourceId(), r.createdAt(), r.readAt()))
                .toList();
        long total = repository.count(userId, unreadOnly);
        return new InboxPage(PageResponse.of(items, page, size, total),
                unreadOnly ? total : repository.count(userId, true));
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.count(userId, true);
    }

    @Override
    public void markRead(UUID userId, UUID notificationId) {
        if (!repository.markRead(notificationId, userId, clock.instant())) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND, "No notification " + notificationId);
        }
    }

    @Override
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId, clock.instant());
    }

    @Override
    public int purgeRead() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteReadBefore(cutoff);
        log.info("Inbox purge: {} read messages older than {} days deleted", deleted, retentionDays);
        return deleted;
    }
}
