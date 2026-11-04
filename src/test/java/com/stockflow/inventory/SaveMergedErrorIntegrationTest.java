package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review of PR #109, item 7: when the write inside {@code saveMerged} fails, PostgreSQL aborts the transaction, and the
 * clean-up {@code set_config} that used to run in a {@code finally} failed too (25P02) and replaced the real error. The
 * real one - here a foreign key, 23503 - must reach the caller, so the API answers 409 and not 500.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SaveMergedErrorIntegrationTest {

    @Autowired StockItemRepository stockItems;
    @Autowired TransactionTemplate tx;
    @Autowired GlobalExceptionHandler handler;

    @Test
    @DisplayName("a failed merge write surfaces as its own integrity violation (409), not the aborted clean-up (500)")
    void theOriginalErrorSurvivesTheCleanup() {
        // A layer at a location that does not exist: the INSERT breaks fk_stock_item_location.
        StockItem nowhere = StockItem.receiveInbound(new Sku("SOFA-3S-GREY"), new LocationId("HCM-ZZ99-9-Z"), null,
                null, Quantity.of(1), Instant.now());

        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(s -> stockItems.saveMerged(nowhere)));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(thrown)).as("the foreign key, not 25P02 'transaction is aborted'").isEqualTo("23503");
        assertThat(handler.handleIntegrity((DataIntegrityViolationException) thrown).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
