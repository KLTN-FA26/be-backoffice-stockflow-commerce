package com.stockflow.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What the sweep calls an orphan, and what it leaves alone. */
class OrphanedFileSweeperTest {

    @TempDir Path directory;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 1};
    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes();

    private LocalFileStorage storage() {
        return new LocalFileStorage(new StorageProperties("stockflow", "", "ap-southeast-1", true, directory.toString(),
                Duration.ofMinutes(10)), Clock.systemUTC());
    }

    private String stored(LocalFileStorage storage, FileCategory category, byte[] bytes, String type, Duration age)
            throws Exception {
        String key = storage.store(category, "x", type, bytes.length, new ByteArrayInputStream(bytes)).key();
        Files.setLastModifiedTime(directory.resolve(key), FileTime.from(Instant.now().minus(age)));
        return key;
    }

    /** A module that names the keys it was given. */
    private static StoredFileReferences owner(FileCategory category, Set<String> named) {
        return new StoredFileReferences() {
            @Override
            public Set<FileCategory> categories() {
                return Set.of(category);
            }

            @Override
            public Set<String> referenced(FileCategory asked, Collection<String> keys) {
                Set<String> found = new HashSet<>(keys);
                found.retainAll(named);
                return found;
            }
        };
    }

    @Test
    void anOldFileNoRecordNamesIsReportedAndOnlyDeletedWhenToldTo() throws Exception {
        var storage = storage();
        String orphan = stored(storage, FileCategory.QC_EVIDENCE, JPEG, "image/jpeg", Duration.ofDays(30));
        String copy = StorageKeys.evidenceCopyOf(orphan, EvidenceCopies.THUMBNAIL);
        storage.storeDerived(copy, "image/jpeg", JPEG);
        String kept = stored(storage, FileCategory.QC_EVIDENCE, JPEG, "image/jpeg", Duration.ofDays(30));
        String young = stored(storage, FileCategory.QC_EVIDENCE, JPEG, "image/jpeg", Duration.ofHours(2));
        String unclaimed = stored(storage, FileCategory.PRODUCT_IMAGE, JPEG, "image/jpeg", Duration.ofDays(400));
        List<StoredFileReferences> owners = List.of(owner(FileCategory.QC_EVIDENCE, Set.of(kept)));

        var reportOnly = new OrphanedFileSweeper(storage, owners, new OrphanSweepProperties(false, Duration.ofDays(7), 2),
                Clock.systemUTC()).sweep();

        assertThat(reportOnly.orphans()).containsExactly(orphan);
        assertThat(reportOnly.checked()).as("the young file is not even considered").isEqualTo(2);
        assertThat(reportOnly.deleted()).isZero();
        assertThat(storage.exists(orphan)).isTrue();

        var deleting = new OrphanedFileSweeper(storage, owners, new OrphanSweepProperties(true, Duration.ofDays(7), 2),
                Clock.systemUTC()).sweep();

        assertThat(deleting.deleted()).isOne();
        assertThat(storage.exists(orphan)).isFalse();
        assertThat(storage.exists(copy)).as("its display copies go with it").isFalse();
        assertThat(storage.exists(kept)).isTrue();
        assertThat(storage.exists(young)).isTrue();
        assertThat(storage.exists(unclaimed)).as("a category nobody claims is never swept").isTrue();
    }

    @Test
    void aListingIsReadInPagesInKeyOrder() throws Exception {
        var storage = storage();
        for (int i = 0; i < 5; i++) {
            stored(storage, FileCategory.QC_EVIDENCE, JPEG, "image/jpeg", Duration.ofDays(30));
        }
        var first = storage.list("qc-evidence/", null, 2);
        var second = storage.list("qc-evidence/", first.get(1).key(), 2);
        var third = storage.list("qc-evidence/", second.get(1).key(), 2);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);
        assertThat(first.get(0).key()).isLessThan(first.get(1).key());
        assertThat(second.get(0).key()).isGreaterThan(first.get(1).key());
        assertThat(storage.list("exports/", null, 10)).isEmpty();
    }
}
