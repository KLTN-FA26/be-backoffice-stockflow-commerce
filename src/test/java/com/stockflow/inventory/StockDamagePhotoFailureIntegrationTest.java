package com.stockflow.inventory;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.inventory.internal.domain.DamageReason;
import com.stockflow.inventory.internal.service.StockDamages;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * BR-STK-003's evidence is stored before any stock moves, inside the report's transaction: when the object store
 * refuses a photo, nothing of the report is left - no stock moved, no report, and the photos already stored are
 * deleted again when the transaction rolls back ({@code FileTransfers}).
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class StockDamagePhotoFailureIntegrationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F',
            0, 1, 1, 0, 0, 1};

    @MockitoSpyBean FileStorage storage;
    @Autowired StockDamages damages;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("the second photo fails to store: no stock change, no report, the first photo deleted again")
    void storeFailureLeavesNothing() {
        String sku = "DMGF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID row = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku, "Fixture",
                    sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Fixture','')", Identifiers.newId(), product, sku);
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, expiry_date, on_hand, reserved, status,
                                                      received_at, version, created_at)
                    VALUES (?, ?, 'HCM-A01-2-B', ?, 5, 0, 'AVAILABLE', ?, 0, NOW())""", row, sku,
                    LocalDate.now().plusDays(90), Timestamp.from(Instant.parse("2026-10-01T03:00:00Z")));
        });
        UUID reporter = tx.execute(s -> ReferenceRows.user(entityManager));
        List<String> stored = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new StorageException("object store unavailable");
            }
            StoredFile file = (StoredFile) invocation.callRealMethod();
            stored.add(file.key());
            return file;
        }).when(storage).store(any(), any(), any(), anyLong(), any());
        try {
            Throwable failure = catchThrowable(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () ->
                    damages.report(new StockDamages.ReportDamageCommand(UUID.randomUUID(), row, 2,
                            DamageReason.BROKEN, null, null, reporter), List.of(photo("a.jpg"), photo("b.jpg")))));

            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).errorCode()).isEqualTo(ErrorCode.STORAGE_ERROR);
        } finally {
            reset(storage);
        }

        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE id = ?", Integer.class, row))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?",
                Integer.class, sku)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'",
                Integer.class, sku)).isZero();
        assertThat(stored).singleElement().satisfies(key -> assertThat(storage.exists(key))
                .as("the photo stored before the failure is deleted with the rollback").isFalse());
    }

    private static FileUpload photo(String name) {
        return new FileUpload(name, "image/jpeg", JPEG.length, new ByteArrayInputStream(JPEG));
    }
}
