package com.stockflow.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * WF-05 E2: an e-invoice's files are locked as part of issuing - at once, for the configured period, and a lock that
 * fails is an error the caller sees, not a log line.
 */
class FileTransfersRetainNowTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final String KEY = "einvoices/2026/11/12/00000000-0000-7000-8000-000000000001.xml";

    private final FileStorage storage = mock(FileStorage.class);
    /** Not read by what is tested here. */
    private final StorageProperties properties = null;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private FileTransfers transfers(boolean lockOn) {
        return transfers(lockOn, clock);
    }

    /** Other records are locked in GOVERNANCE, so an e-invoice's COMPLIANCE lock shows it has its own mode. */
    private FileTransfers transfers(boolean lockOn, Clock at) {
        return new FileTransfers(storage, properties, at, new ObjectLockProperties(lockOn,
                ObjectLockProperties.Mode.GOVERNANCE, Duration.ofDays(1), Duration.ofDays(2),
                java.time.Period.ofYears(10), ObjectLockProperties.Mode.COMPLIANCE));
    }

    @Test
    @DisplayName("locked at once, with no transaction to wait for, ten years after the end of the year, in COMPLIANCE")
    void lockedAtOnce() {
        transfers(true).retainNow(KEY, RecordRetention.EINVOICE);

        verify(storage).retain(KEY, Instant.parse("2037-01-01T17:00:00Z") /* 2037-01-02 00:00 in Viet Nam */,
                ObjectLockProperties.Mode.COMPLIANCE);
    }

    @Test
    @DisplayName("E0 M4: the year that counts is Viet Nam's - 00:30 on New Year's Day there is already the next year")
    void theYearIsVietNams() {
        transfers(true, Clock.fixed(Instant.parse("2026-12-31T16:30:00Z"), ZoneOffset.UTC))
                .retainNow(KEY, RecordRetention.EINVOICE);
        verify(storage).retain(KEY, Instant.parse("2037-01-01T17:00:00Z"), ObjectLockProperties.Mode.COMPLIANCE);

        transfers(true, Clock.fixed(Instant.parse("2026-12-31T17:30:00Z"), ZoneOffset.UTC))
                .retainNow(KEY, RecordRetention.EINVOICE);
        verify(storage).retain(KEY, Instant.parse("2038-01-01T17:00:00Z"), ObjectLockProperties.Mode.COMPLIANCE);
    }

    @Test
    @DisplayName("a lock the store refuses, or a store that cannot be reached, fails the caller")
    void aFailedLockFails() {
        doThrow(new StorageException("the bucket has no Object Lock")).when(storage).retain(any(), any(), any());
        assertThatThrownBy(() -> transfers(true).retainNow(KEY, RecordRetention.EINVOICE))
                .isInstanceOf(StorageException.class).hasMessageContaining("no Object Lock");

        doThrow(new IllegalStateException("connection reset")).when(storage).retain(any(), any(), any());
        assertThatThrownBy(() -> transfers(true).retainNow(KEY, RecordRetention.EINVOICE))
                .isInstanceOf(StorageException.class).hasMessageContaining(KEY);
    }

    @Test
    @DisplayName("with Object Lock off nothing is locked; a key that is not one of ours is refused either way")
    void offLocksNothing() {
        transfers(false).retainNow(KEY, RecordRetention.EINVOICE);
        verifyNoInteractions(storage);

        assertThatThrownBy(() -> transfers(true).retainNow("../etc/passwd", RecordRetention.EINVOICE))
                .isInstanceOf(RuntimeException.class);
    }
}
