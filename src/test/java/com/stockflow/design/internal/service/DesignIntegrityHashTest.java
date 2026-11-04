package com.stockflow.design.internal.service;

import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The integrity hash must not depend on whether the virus scanner happens to read the stream. It used
 * to be computed as a side effect of the scan, so with scanning switched off (the local profile) it
 * was the hash of zero bytes and every confirmation and every packing check reported a mismatch.
 */
class DesignIntegrityHashTest {

    private static final byte[] CONTENT = "the confirmed artifact".getBytes(StandardCharsets.UTF_8);

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private DesignServiceImpl service(UploadInspection inspection, DesignSnapshotJpaEntity snapshot) {
        var snapshots = mock(DesignSnapshotJpaRepository.class);
        when(snapshots.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));
        var storage = mock(FileStorage.class);
        when(storage.download(snapshot.getArtifactUrl())).thenAnswer(call -> new ByteArrayInputStream(CONTENT));
        return new DesignServiceImpl(snapshots, mock(DesignDraftJpaRepository.class), storage,
                mock(ProductService.class), inspection, mock(FileTransfers.class));
    }

    @Test
    void theHashIsCorrectWhenTheScannerIsOff() throws Exception {
        String expected = sha256(CONTENT);
        var snapshot = new DesignSnapshotJpaEntity(UUID.randomUUID(), UUID.randomUUID(), expected,
                "design-renders/2026/09/20/x.png", Instant.now());
        var off = new UploadInspection("localhost", 1, false);

        var result = service(off, snapshot).verifyForFulfillment(snapshot.getId(), expected);

        assertThat(result.matching()).isTrue();
        assertThat(result.actualChecksum()).isEqualTo(expected);
    }

    @Test
    void aChangedArtifactIsStillReportedAsAMismatch() throws Exception {
        String recorded = sha256("what was confirmed".getBytes(StandardCharsets.UTF_8));
        var snapshot = new DesignSnapshotJpaEntity(UUID.randomUUID(), UUID.randomUUID(), recorded,
                "design-renders/2026/09/20/x.png", Instant.now());
        var off = new UploadInspection("localhost", 1, false);

        var result = service(off, snapshot).verifyForFulfillment(snapshot.getId(), recorded);

        assertThat(result.matching()).isFalse();
        assertThat(result.actualChecksum()).isEqualTo(sha256(CONTENT));
    }
}
