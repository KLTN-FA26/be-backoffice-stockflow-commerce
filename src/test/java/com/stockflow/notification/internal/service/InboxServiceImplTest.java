package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.internal.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Posting: who gets a row, how often, and what is reported back. The SQL's {@code ON CONFLICT} is in the IT. */
class InboxServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    private final UserNotificationRepository repository = mock(UserNotificationRepository.class);
    private InboxServiceImpl inbox;
    private final InboxMessage message = new InboxMessage("INVENTORY_ALERT", InboxSeverity.WARN, "3 lots near expiry",
            null, "/admin/inventory/alerts", "STOCK_ALERT", "a-1", "EXPIRING:HCM:2026-10-14");

    @BeforeEach
    void setUp() {
        inbox = new InboxServiceImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void postsOneRowPerRecipientAndCountsTheNewOnes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), eq(a), eq(message), eq(NOW))).thenReturn(true);
        when(repository.insertIfAbsent(any(), eq(b), eq(message), eq(NOW))).thenReturn(false); // already had the key

        assertThat(inbox.post(List.of(a, b), message)).isEqualTo(1);

        verify(repository).insertIfAbsent(any(), eq(a), eq(message), eq(NOW));
        verify(repository).insertIfAbsent(any(), eq(b), eq(message), eq(NOW));
    }

    @Test
    void aRecipientListedTwiceOrNullIsPostedOnce() {
        UUID a = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), eq(a), eq(message), any())).thenReturn(true);

        assertThat(inbox.post(Arrays.asList(a, null, a), message)).isEqualTo(1);

        verify(repository).insertIfAbsent(any(), eq(a), eq(message), any());
        verify(repository, never()).insertIfAbsent(any(), eq((UUID) null), any(), any());
    }

    @Test
    void noRecipientsPostsNothing() {
        assertThat(inbox.post(List.of(), message)).isZero();
        assertThat(inbox.post(null, message)).isZero();
        assertThat(inbox.post(Arrays.asList((UUID) null), message)).isZero();

        verifyNoInteractions(repository);
    }

    @Test
    void everyRowGetsItsOwnId() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), any(), any(), any())).thenReturn(true);

        assertThat(inbox.post(List.of(a, b, c), message)).isEqualTo(3);

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(repository, org.mockito.Mockito.times(3)).insertIfAbsent(ids.capture(), any(), any(), any());
        List<UUID> captured = new ArrayList<>(ids.getAllValues());
        assertThat(new HashSet<>(captured)).hasSize(3);
    }

    @Test
    void aMessageIsRequired() {
        assertThatThrownBy(() -> inbox.post(Set.of(UUID.randomUUID()), null)).isInstanceOf(NullPointerException.class);
    }
}
