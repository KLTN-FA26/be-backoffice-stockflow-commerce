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
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF4-OVERDUE-TRIGGER (kltn-docs 15 §4.3 step 11), WF-05 BE-49: the daily scan announces every installment overdue
 * today that was not announced for its current due date - whatever its age - writes that date down, and publishes one
 * event per installment it marked. It asks the installment search for the one definition of overdue, on the business
 * day; there is no look-back any more.
 */
class OverdueInstallmentScanTest {

    /** 2026-11-17 03:00 UTC is 10:00 on the 17th in Vietnam. */
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final InstallmentSearch search = mock(InstallmentSearch.class);
    private final InstallmentBook book = mock(InstallmentBook.class);
    private final List<Object> published = new ArrayList<>();
    private final InstallmentService service = new InstallmentService(book, search, mock(InstallmentOrders.class),
            new CompanyBankProperties("Bank", "0001", "StockFlow"), Clock.fixed(NOW, ZoneOffset.UTC), published::add);

    /** An installment on signing, signed on {@code signedOn} and due {@code days} days after that. */
    private static Installment dueOn(int seq, long amount, LocalDate signedOn, int days) {
        Installment installment = Installment.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "HD-20261110-000001-1", "SO-20261110-000001", seq, "Part " + seq,
                InstallmentMilestone.ON_SIGNING, null, null, BigDecimal.valueOf(amount), "VND", days);
        installment.reach(signedOn.atStartOfDay().toInstant(ZoneOffset.UTC), signedOn);
        return installment;
    }

    @Test
    @DisplayName("asks for what is overdue on the business day and not announced for its due date, marks it, and "
            + "publishes one event each")
    void announcesWhatTheSearchFinds() {
        Installment first = dueOn(1, 6_000_000, TODAY.minusDays(5), 2);
        Installment second = dueOn(2, 1_500_000, TODAY.minusDays(4), 2);
        when(search.installments(any(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
        when(book.markOverdueAnnounced(any(), any())).thenReturn(true);

        int announced = service.announceNewlyOverdue();

        assertThat(announced).isEqualTo(2);
        ArgumentCaptor<InstallmentSearch.InstallmentFilter> filter = ArgumentCaptor
                .forClass(InstallmentSearch.InstallmentFilter.class);
        verify(search).installments(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().overdue()).isTrue();
        assertThat(filter.getValue().overdueOn()).as("the business day, not the server's").isEqualTo(TODAY);
        assertThat(filter.getValue().unannounced()).as("not told for its current due date").isTrue();
        assertThat(filter.getValue().dueFrom()).as("BE-49: no look-back").isNull();
        verify(book).markOverdueAnnounced(first.id(), TODAY.minusDays(3));
        verify(book).markOverdueAnnounced(second.id(), TODAY.minusDays(2));
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
    @DisplayName("BE-49: an installment overdue for weeks - reached late, or moved - is announced all the same")
    void oldOverdueIsAnnounced() {
        Installment longOverdue = dueOn(1, 100_000, TODAY.minusDays(40), 2);
        when(search.installments(any(), any())).thenReturn(new PageImpl<>(List.of(longOverdue)));
        when(book.markOverdueAnnounced(longOverdue.id(), TODAY.minusDays(38))).thenReturn(true);

        assertThat(service.announceNewlyOverdue()).isEqualTo(1);
        assertThat(((InstallmentOverdue) published.getFirst()).dueDate()).isEqualTo(TODAY.minusDays(38));
    }

    @Test
    @DisplayName("one the mark could not take - paid or moved meanwhile, or held by a receipt right now - is not "
            + "announced this time")
    void notMarkedNotAnnounced() {
        Installment held = dueOn(1, 100_000, TODAY.minusDays(5), 2);
        when(search.installments(any(), any())).thenReturn(new PageImpl<>(List.of(held)));
        when(book.markOverdueAnnounced(any(), any())).thenReturn(false);

        assertThat(service.announceNewlyOverdue()).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("nothing to announce: nothing published")
    void nothingToAnnounce() {
        when(search.installments(any(), any())).thenReturn(Page.empty());

        assertThat(service.announceNewlyOverdue()).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("reads the first page again while it brings new ones - what was marked has left it - and stops when "
            + "a page brings nothing new")
    void readsUntilNothingNew() {
        Installment onFirstRead = dueOn(1, 100_000, TODAY.minusDays(5), 2);
        Installment onSecondRead = dueOn(2, 200_000, TODAY.minusDays(5), 2);
        when(search.installments(any(), any()))
                .thenReturn(new PageImpl<>(List.of(onFirstRead), PageRequest.of(0, 1), 2))
                .thenReturn(new PageImpl<>(List.of(onSecondRead), PageRequest.of(0, 1), 2));
        when(book.markOverdueAnnounced(any(), any())).thenReturn(true);

        assertThat(service.announceNewlyOverdue()).isEqualTo(2);
        assertThat(published).hasSize(2);
    }

    @Test
    @DisplayName("the job runs after midnight in Vietnam under a scheduler lock, and calls the scan")
    void theJobIsScheduledAndLocked() throws Exception {
        Scheduled scheduled = OverdueInstallmentScanJob.class.getMethod("scan").getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(scheduled.cron()).contains("0 20 0 * * *");
        SchedulerLock lock = OverdueInstallmentScanJob.class.getMethod("scan").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("payment.overdue-installment-scan");
        assertThat(lock.lockAtMostFor()).isEqualTo(OverdueInstallmentScanJob.LOCK_AT_MOST);

        InstallmentService installments = mock(InstallmentService.class);
        new OverdueInstallmentScanJob(installments).scan();
        verify(installments).announceNewlyOverdue();
    }
}
