package com.stockflow.procurement.internal.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.internal.repository.SubcontractOrderStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Develop #99 log hygiene (lane B finding B-7): a reason purchasing typed - here one that tries to forge a log line -
 * is kept on the purchase order, never written to the log. Only ids and counts are.
 */
class SubcontractLogHygieneTest {

    private static final String TYPED = "resend after QC\r\n2026-10-13 INFO FORGED admin approved everything";

    private final Logger logs = (Logger) LoggerFactory.getLogger("com.stockflow.procurement");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final SubcontractOrderStore store = mock(SubcontractOrderStore.class);
    private final ProcurementService procurement = mock(ProcurementService.class);
    private final SubcontractPurchasingImpl purchasing = new SubcontractPurchasingImpl(store, mock(InventoryService.class),
            mock(InventoryControlService.class), procurement, Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"),
            ZoneOffset.UTC), mock(ApprovalLimits.class));
    private final UUID po = UUID.randomUUID();

    @BeforeEach
    void capture() {
        appender.start();
        logs.addAppender(appender);
        when(store.findById(eq(po), anyBoolean())).thenReturn(Optional.of(new SubcontractOrderStore.Row(po, "SPO-1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DRAFT", UUID.randomUUID(),
                new BigDecimal("10.000"), new BigDecimal("1500.00"), "VND", new BigDecimal("15000"),
                LocalDate.of(2026, 10, 20))));
    }

    @AfterEach
    void release() {
        logs.detachAppender(appender);
    }

    @Test
    void theTypedReasonOfASupplementOrAClosureIsNotLogged() {
        purchasing.supplement(po, 2, TYPED, UUID.randomUUID());
        purchasing.closeForProduction(po, TYPED, UUID.randomUUID());

        verify(procurement).cancel(eq(po), org.mockito.ArgumentMatchers.any(), eq(TYPED));   // kept on the PO
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(line -> assertThat(line).doesNotContain("FORGED").doesNotContain("resend after QC"))
                .anySatisfy(line -> assertThat(line).contains("SPO-1"));
    }
}
