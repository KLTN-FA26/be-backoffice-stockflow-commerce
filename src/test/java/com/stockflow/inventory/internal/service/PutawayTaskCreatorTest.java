package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.contracts.ReceiptStockReadyForPutaway.Line;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-92: received goods ready for putaway open one task per carton (kltn-docs 05 BR-08). Since team plan 4C-2 a
 * receipt puts every unit into a carton, so units outside one open no task.
 */
class PutawayTaskCreatorTest {

    private static final UUID RECEIPT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    private final PutawayTasks tasks = mock(PutawayTasks.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final PutawayTaskCreator creator = new PutawayTaskCreator(tasks, stockPackages);

    /** A carton of 24 received on {@code line}: in stock in {@code stockStatus}, CONSUMED when that is null. */
    private static StockPackage carton(UUID line, StockStatus stockStatus) {
        boolean inStock = stockStatus != null;
        return new StockPackage(new StockPackageId(UUID.randomUUID()),
                "LPN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                new com.stockflow.common.domain.Sku("CUP-12OZ"), UUID.randomUUID(), UUID.randomUUID(), 24, NOW,
                new LocationId("HCM-RCV01"), inStock ? StockPackageStatus.IN_STOCK : StockPackageStatus.CONSUMED,
                stockStatus, UUID.randomUUID(), "L1", NOW, line, inStock ? null : NOW, List.of(), 0L);
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
    @DisplayName("a line with no cartons opens no task: units outside a carton are not put away")
    void looseOnly() {
        creator.on(event(line(LINE, 100)));

        assertThat(opened()).isEmpty();
    }

    @Test
    @DisplayName("INBOUND cartons open one task each, sized by their count; the units beyond them open none")
    void cartonsPlusLooseRest() {
        StockPackage a = carton(LINE, StockStatus.INBOUND);
        StockPackage b = carton(LINE, StockStatus.INBOUND);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(a, b));

        creator.on(event(line(LINE, 60)));

        assertThat(opened()).containsExactlyInAnyOrder(
                new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, a.id().value()),
                new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, b.id().value()));
    }

    @Test
    @DisplayName("when the cartons hold the whole line there is no loose task")
    void noLooseWhenCartonsCoverTheLine() {
        StockPackage a = carton(LINE, StockStatus.INBOUND);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(a));

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, LINE, "CUP-12OZ", 24, a.id().value()));
    }

    @Test
    @DisplayName("a carton that is not INBOUND (quarantined, or already put away) opens no carton task")
    void cartonWithoutInboundUnitsOpensNothing() {
        StockPackage putAway = carton(LINE, StockStatus.AVAILABLE);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(putAway));

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).isEmpty();
    }

    @Test
    @DisplayName("a carton that is not IN_STOCK opens nothing")
    void closedCartonOpensNothing() {
        StockPackage consumed = carton(LINE, null);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(consumed));

        creator.on(event(line(LINE, 24)));

        assertThat(opened()).isEmpty();
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
        StockPackage ofOther = carton(other, StockStatus.INBOUND);
        when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(ofOther));

        creator.on(event(line(LINE, 100), line(other, 24)));

        assertThat(opened()).containsExactly(new NewPutawayTask(RECEIPT, other, "CUP-12OZ", 24, ofOther.id().value()));
    }
}
