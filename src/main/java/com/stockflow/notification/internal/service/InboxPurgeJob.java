package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, read inbox messages older than the retention are deleted (default 90 days, 03:45 business
 * time: after the 03:30 audit purge). Unread messages are never purged. One set-based DELETE: running it
 * twice deletes nothing more. ShedLock keeps two instances from running it at once.
 */
@Component
class InboxPurgeJob {

    private final UserInbox inbox;

    InboxPurgeJob(UserInbox inbox) {
        this.inbox = inbox;
    }

    @Scheduled(cron = "${stockflow.notification.inbox-purge-cron:0 45 3 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "notification.purgeInbox", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void purgeInbox() {
        inbox.purgeRead();
    }
}
