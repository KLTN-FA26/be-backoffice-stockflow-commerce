package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.AreaDetails;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.StorageClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A storage area created and <b>committed for real</b>: {@code tg_storage_location_owned} is
 * deferred to commit, so only a commit proves the new location is owned by its area - and
 * {@code fk_area_location} is not deferrable, so only a real insert proves the location went first.
 *
 * <p>Not {@code @Transactional}. Area RCV02 stands on free floor of the demo warehouse HCM
 * (20-25 x 25-30) and is removed afterwards, area first, in a transaction of its own: the pool hands
 * out connections with auto-commit off, so a bare {@code JdbcTemplate} delete would be rolled back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class AreaCreationCommitIntegrationTest {

    @Autowired AreaLayoutService areas;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void removeTheArea() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("delete from warehouse.area where code = 'RCV02'");
            jdbc.update("delete from warehouse.storage_location where location_code = 'HCM-RCV02'");
        });
    }

    private AreaSummary create(String code, Footprint footprint) {
        return areas.createArea(new AreaCommands.CreateArea(WAREHOUSE_HCM, code, new AreaDetails(AreaType.RECEIVING,
                "Khu " + code, footprint, false, null, new LocationSettings(null, null, false, true))));
    }

    private static Footprint at(String x, String y, String width, String length) {
        return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width), new BigDecimal(length), 0);
    }

    @Test
    @DisplayName("rcv02 in HCM commits with location HCM-RCV02; RCV01 is already taken")
    void storageAreaCommits() {
        AreaSummary area = create("rcv02", at("20", "25", "5", "5"));

        assertThat(area.code()).isEqualTo("RCV02");
        assertThat(area.locationCode()).isEqualTo("HCM-RCV02");
        assertThat(area.storageClass()).isEqualTo(StorageClass.NORMAL);
        assertThat(jdbc.queryForObject("select count(*) from warehouse.area a join warehouse.storage_location l "
                + "on l.id = a.location_id where l.location_code = 'HCM-RCV02' and l.kind = 'AREA' "
                + "and l.status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(errorOf(() -> create("rcv01", at("20", "31", "2", "2"))))
                .isEqualTo(ErrorCode.AREA_CODE_ALREADY_EXISTS);
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }
}
