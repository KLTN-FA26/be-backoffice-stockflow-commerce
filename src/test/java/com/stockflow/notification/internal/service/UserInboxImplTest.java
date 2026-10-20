package com.stockflow.notification.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.internal.repository.UserNotificationRepository;
import com.stockflow.notification.internal.repository.UserNotificationRepository.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The read state and the paging arithmetic of one user's inbox; the isolation between users is in the IT. */
class UserInboxImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    private final UserNotificationRepository repository = mock(UserNotificationRepository.class);
    private final UUID me = UUID.randomUUID();
    private UserInboxImpl inbox;

    @BeforeEach
    void setUp() {
        inbox = new UserInboxImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC), 90);
    }

    private static Row row(Instant readAt) {
        return new Row(UUID.randomUUID(), "INVENTORY_ALERT", InboxSeverity.INFO, "t", null, null, null, null,
                NOW.minusSeconds(60), readAt);
    }

    @Test
    void listsAPageAndCountsUnreadInAll() {
        when(repository.page(me, false, 2, 4)).thenReturn(List.of(row(null), row(NOW)));
        when(repository.count(me, false)).thenReturn(7L);
        when(repository.count(me, true)).thenReturn(3L);

        UserInbox.InboxPage page = inbox.list(me, false, 2, 2);

        assertThat(page.page().items()).hasSize(2);
        assertThat(page.page().page()).isEqualTo(2);
        assertThat(page.page().totalElements()).isEqualTo(7);
        assertThat(page.page().totalPages()).isEqualTo(4);
        assertThat(page.page().hasNext()).isTrue();
        assertThat(page.page().hasPrevious()).isTrue();
        assertThat(page.unreadCount()).isEqualTo(3);
        assertThat(page.page().items().get(1).readAt()).isEqualTo(NOW);
    }

    @Test
    void theUnreadFilterReusesItsTotalAsTheBadge() {
        when(repository.page(me, true, 20, 0)).thenReturn(List.of(row(null)));
        when(repository.count(me, true)).thenReturn(1L);

        UserInbox.InboxPage page = inbox.list(me, true, 0, 20);

        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(page.page().hasNext()).isFalse();
        verify(repository, org.mockito.Mockito.times(1)).count(me, true);
    }

    @Test
    void refusesAPageOrSizeOutOfRangeBeforeTouchingTheDatabase() {
        for (int[] bad : new int[][] {{-1, 20}, {0, 0}, {0, 201}}) {
            assertThatThrownBy(() -> inbox.list(me, false, bad[0], bad[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER));
        }
        verify(repository, never()).page(any(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt(), anyLong());
    }

    @Test
    void marksOneReadAtTheClockTime() {
        UUID id = UUID.randomUUID();
        when(repository.markRead(id, me, NOW)).thenReturn(true);

        inbox.markRead(me, id);

        verify(repository).markRead(id, me, NOW);
    }

    @Test
    void aMessageThatIsNotTheUsersIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.markRead(id, me, NOW)).thenReturn(false);

        assertThatThrownBy(() -> inbox.markRead(me, id)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    void markAllReadReportsHowManyWereUnread() {
        when(repository.markAllRead(me, NOW)).thenReturn(4);

        assertThat(inbox.markAllRead(me)).isEqualTo(4);
    }

    @Test
    void purgesReadMessagesOlderThanTheRetention() {
        when(repository.deleteReadBefore(any())).thenReturn(5);

        assertThat(inbox.purgeRead()).isEqualTo(5);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteReadBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(Instant.parse("2026-07-16T03:00:00Z")); // 90 days before NOW
    }

    @Test
    void aRetentionOfLessThanADayIsAConfigurationError() {
        assertThatThrownBy(() -> new UserInboxImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
