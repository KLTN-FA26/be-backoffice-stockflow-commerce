package com.stockflow.payment.internal.service;

import com.stockflow.contracts.InstallmentOverdue;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF4-OVERDUE-TRIGGER (kltn-docs 15 §4.3 step 11): the daily scan announces the installments that turned overdue lately.
 * It asks the installment search for the one definition of overdue - past the due day, still owing - on the business
 * day, with a lower bound on the due day so the history is not announced; and it publishes one event per installment
 * found, page after page.
 */
class OverdueInstallmentScanTest {

    /** 2026-11-17 03:00 UTC is 10:00 on the 17th in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final InstallmentSearch search = mock(InstallmentSearch.class);
    private final List<Object> published = new ArrayList<>();
    private final InstallmentService service = new InstallmentService(mock(InstallmentBook.class), search,
            mock(InstallmentOrders.class), new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(NOW, ZoneOffset.UTC), published::add);

    /** An installment on signing, signed {@code daysAgo} days ago and due {@code days} days after that. */
    private static Installment dueOn(int seq, long amount, LocalDate signedOn, int days) {
        Installment installment = Installment.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "HD-20261110-000001-1", "SO-20261110-000001", seq, "Part " + seq,
                InstallmentMilestone.ON_SIGNING, null, null, BigDecimal.valueOf(amount), "VND", days);
        installment.reach(signedOn.atStartOfDay().toInstant(ZoneOffset.UTC), signedOn);
        return installment;
    }

    @Test
    @DisplayName("asks for what is overdue on the business day, due within the look-back, and publishes one event each")
    void announcesWhatTheSearchFinds() {
        Installment first = dueOn(1, 6_000_000, TODAY.minusDays(5), 2);
        Installment second = dueOn(2, 1_500_000, TODAY.minusDays(4), 2);
        when(search.installments(any(), any())).thenReturn(new PageImpl<>(List.of(first, second)));

        int announced = service.announceNewlyOverdue(3);

        assertThat(announced).isEqualTo(2);
        ArgumentCaptor<InstallmentSearch.InstallmentFilter> filter = ArgumentCaptor
                .forClass(InstallmentSearch.InstallmentFilter.class);
        verify(search).installments(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().overdue()).isTrue();
        assertThat(filter.getValue().overdueOn()).as("the business day, not the server's").isEqualTo(TODAY);
        assertThat(filter.getValue().dueFrom()).as("no history: three days back").isEqualTo(TODAY.minusDays(3));
        assertThat(published).hasSize(2).allSatisfy(event -> assertThat(event).isInstanceOf(InstallmentOverdue.class));
        InstallmentOverdue event = (InstallmentOverdue) published.getFirst();
        assertThat(event.installmentId()).isEqualTo(first.id());
        assertThat(event.contractNumber()).isEqualTo("HD-20261110-000001-1");
        assertThat(event.orderNumber()).isEqualTo("SO-20261110-000001");
        assertThat(event.orderId()).isEqualTo(first.orderId());
        assertThat(event.customerId()).isEqualTo(first.customerId());
        assertThat(event.seq()).isEqualTo(1);
        assertThat(event.dueDate()).isEqualTo(TODAY.minusDays(3));
        assertThat(event.outstanding()).isEqualByComparingTo("6000000");
        assertThat(event.currency()).isEqualTo("VND");
    }

    @Test
    @DisplayName("nothing overdue lately: nothing published")
    void nothingToAnnounce() {
        when(search.installments(any(), any())).thenReturn(Page.empty());

        assertThat(service.announceNewlyOverdue(3)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("reads on while there are more pages")
    void readsEveryPage() {
        Installment onFirstPage = dueOn(1, 100_000, TODAY.minusDays(5), 2);
        Installment onSecondPage = dueOn(2, 200_000, TODAY.minusDays(5), 2);
        when(search.installments(any(), any()))
                .thenReturn(new PageImpl<>(List.of(onFirstPage), org.springframework.data.domain.PageRequest.of(0, 1), 2))
                .thenReturn(new PageImpl<>(List.of(onSecondPage), org.springframework.data.domain.PageRequest.of(1, 1), 2));

        assertThat(service.announceNewlyOverdue(3)).isEqualTo(2);
    }

    @Test
    @DisplayName("a look-back below one day is refused")
    void lookbackIsAtLeastOneDay() {
        assertThatThrownBy(() -> service.announceNewlyOverdue(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the job runs after midnight in Vietnam under a scheduler lock, and calls the scan with its look-back")
    void theJobIsScheduledAndLocked() throws Exception {
        Scheduled scheduled = OverdueInstallmentScanJob.class.getMethod("scan").getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(scheduled.cron()).contains("0 20 0 * * *");
        SchedulerLock lock = OverdueInstallmentScanJob.class.getMethod("scan").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("payment.overdue-installment-scan");
        assertThat(lock.lockAtMostFor()).isEqualTo(OverdueInstallmentScanJob.LOCK_AT_MOST);

        InstallmentService installments = mock(InstallmentService.class);
        new OverdueInstallmentScanJob(installments, 3).scan();
        verify(installments).announceNewlyOverdue(3);
    }
}
