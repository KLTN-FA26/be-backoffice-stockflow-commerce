package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The nightly purge is wired the way the other jobs are: business-time cron, one instance at a time. */
class InboxPurgeJobTest {

    @Test
    void delegatesToThePurge() {
        UserInbox inbox = mock(UserInbox.class);

        new InboxPurgeJob(inbox).purgeInbox();

        verify(inbox).purgeRead();
    }

    @Test
    void runsAt0345BusinessTimeUnderAShedLock() throws Exception {
        Method job = InboxPurgeJob.class.getMethod("purgeInbox");

        Scheduled scheduled = job.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${stockflow.notification.inbox-purge-cron:0 45 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo(BusinessCalendar.ZONE_NAME).isEqualTo("Asia/Ho_Chi_Minh");
        SchedulerLock lock = job.getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("notification.purgeInbox");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT10M");
    }
}
