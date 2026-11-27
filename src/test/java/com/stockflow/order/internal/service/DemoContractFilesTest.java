package com.stockflow.order.internal.service;

import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StoredFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DemoContractFiles}: a seeded contract's missing files get the application's own rendering, once per contract,
 * stored under the seeded keys; what is already there, and anything outside {@code documents/demo/}, is left alone.
 */
class DemoContractFilesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SalesContracts contracts = mock(SalesContracts.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final DemoContractFiles files = new DemoContractFiles(jdbc, contracts, storage);

    @AfterEach
    void noScopeLeftBehind() {
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void seeded(DemoContractFiles.SeededContract... rows) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(rows));
    }

    @Test
    void rendersOnceAndStoresUnderEveryMissingSeededKey() {
        UUID order = UUID.randomUUID();
        seeded(new DemoContractFiles.SeededContract(order, "HD-1", "documents/demo/HD-1.pdf",
                "documents/demo/HD-1-ky.pdf"));
        when(storage.exists(anyString())).thenReturn(false);
        when(contracts.preview(order)).thenAnswer(call -> {
            assertThat(DataScopeContext.current()).as("rendered under a system scope").isPresent();
            return new byte[] {'%', 'P', 'D', 'F'};
        });
        when(storage.store(eq(FileCategory.DOCUMENT), eq("HD-1.pdf"), eq("application/pdf"), anyLong(),
                any(InputStream.class))).thenReturn(new StoredFile("documents/2026/11/24/staged.pdf", "HD-1.pdf",
                        "application/pdf", 4, Instant.now()));

        assertThat(files.materialize()).isEqualTo(2);

        verify(contracts, times(1)).preview(order);
        verify(storage).copy("documents/2026/11/24/staged.pdf", "documents/demo/HD-1.pdf");
        verify(storage).copy("documents/2026/11/24/staged.pdf", "documents/demo/HD-1-ky.pdf");
        verify(storage).delete("documents/2026/11/24/staged.pdf");
    }

    @Test
    void leavesWhatIsThereAndWhatTheApplicationStored() {
        UUID there = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        seeded(new DemoContractFiles.SeededContract(there, "HD-2", "documents/demo/HD-2.pdf", null),
                new DemoContractFiles.SeededContract(live, "HD-3", "documents/2026/11/20/abc.pdf",
                        "documents/2026/11/21/def.pdf"));
        when(storage.exists("documents/demo/HD-2.pdf")).thenReturn(true);

        assertThat(files.materialize()).isZero();

        verify(contracts, never()).preview(any());
        verify(storage, never()).exists("documents/2026/11/20/abc.pdf");
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    void oneContractFailingDoesNotStopTheOthers() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        seeded(new DemoContractFiles.SeededContract(broken, "HD-4", "documents/demo/HD-4.pdf", null),
                new DemoContractFiles.SeededContract(fine, "HD-5", "documents/demo/HD-5.pdf", null));
        when(storage.exists(anyString())).thenReturn(false);
        when(contracts.preview(broken)).thenThrow(new IllegalStateException("no live contract"));
        when(contracts.preview(fine)).thenReturn(new byte[] {1});
        when(storage.store(eq(FileCategory.DOCUMENT), eq("HD-5.pdf"), eq("application/pdf"), anyLong(),
                any(InputStream.class))).thenReturn(new StoredFile("documents/x/staged.pdf", "HD-5.pdf",
                        "application/pdf", 1, Instant.now()));

        assertThat(files.materialize()).isOne();

        verify(storage).copy("documents/x/staged.pdf", "documents/demo/HD-5.pdf");
    }
}
