package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.contracts.ReceiptStockReadyForPutaway.Line;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-92: received goods ready for putaway open one task per carton and one loose task for the rest (kltn-docs 05). */
class PutawayTaskCreatorTest {

    private static final UUID RECEIPT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final PutawayTaskCreator creator = new PutawayTaskCreator(tasks, stockPackages);

    private static StockPackage carton(UUID line, StockPackageStatus status) {
        Instant closedAt = status.isClosed() ? NOW : null;
        return new StockPackage(new StockPackageId(UUID.randomUUID()),
                "LPN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), UUID.randomUUID(),
                UUID.randomUUID(), 24, 24, new LocationId("HCM-RCV01"), status, false, line, closedAt, 0L);
    }

    private static ReceiptStockReadyForPutaway event(Line... lines) {
        return new ReceiptStockReadyForPutaway(RECEIPT, "GR-1", WAREHOUSE, List.of(lines));
    }

    private static Line line(UUID id, int quantity) {
        return new Line(id, "CUP-12OZ", "L1", "HCM-RCV01", quantity);
    }

    private List<NewPutawayTask> opened() {
        ArgumentCaptor<NewPutawayTask> captor = ArgumentCaptor.forClass(NewPutawayTask.class);
        verify(tasks, org.mockito.Mockito.atLeast(0)).open(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a line with no cartons opens one loose task for all of its units")
    void looseOnly() {
        creator.on(event(line(LINE, 100)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 100, null));
    }

    @Test
    @DisplayName("cartons open one task each, sized by their INBOUND units; the units beyond them are one loose task")
    void cartonsPlusLooseRest() {
        StockPackage a = carton(LINE, StockPackageStatus.IN_STOCK);
        StockPackage b = carton(LINE, StockPackageStatus.IN_STOCK);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(a, b));
        when(stockPackages.inboundOnHandOf(anyCollection())).thenReturn(Map.of(a.id(), 24, b.id(), 24));

        creator.on(event(line(LINE, 60)));

        assertThat(opened()).containsExactlyInAnyOrder(
                new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, a.id().value()),
                new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, b.id().value()),
                new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 12, null));
    }

    @Test
    @DisplayName("when the cartons hold the whole line there is no loose task")
    void noLooseWhenCartonsCoverTheLine() {
        StockPackage a = carton(LINE, StockPackageStatus.IN_STOCK);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(a));
        when(stockPackages.inboundOnHandOf(anyCollection())).thenReturn(Map.of(a.id(), 24));

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, a.id().value()));
    }

    @Test
    @DisplayName("a carton with no INBOUND units (QC rejected it, or it is already put away) opens no carton task")
    void cartonWithoutInboundUnitsOpensNothing() {
        StockPackage putAway = carton(LINE, StockPackageStatus.IN_STOCK);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(putAway));
        when(stockPackages.inboundOnHandOf(anyCollection())).thenReturn(Map.of());

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, null));
    }

    @Test
    @DisplayName("a carton that is not IN_STOCK opens nothing")
    void closedCartonOpensNothing() {
        StockPackage consumed = carton(LINE, StockPackageStatus.CONSUMED);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(consumed));
        when(stockPackages.inboundOnHandOf(anyCollection())).thenReturn(Map.of(consumed.id(), 24));

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).extracting(NewPutawayTask::stockPackageId).containsOnlyNulls();
    }

    @Test
    @DisplayName("a line that already has a putaway task is skipped whole: a redelivery opens nothing")
    void redeliveryIsSkipped() {
        when(tasks.tasksOfReceipt(RECEIPT)).thenReturn(List.of(new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE,
                "CUP-12OZ", 100, null, null, "OPEN")));

        creator.on(event(line(LINE, 100)));

        verify(tasks, never()).open(any());
    }

    @Test
    @DisplayName("only the lines without a task are processed")
    void otherLinesStillProcessed() {
        UUID other = UUID.randomUUID();
        when(tasks.tasksOfReceipt(RECEIPT)).thenReturn(List.of(new PutawayTaskView(UUID.randomUUID(), RECEIPT, LINE,
                "CUP-12OZ", 100, null, null, "OPEN")));

        creator.on(event(line(LINE, 100), line(other, 30)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, other, "CUP-12OZ", 30, null));
    }
}
