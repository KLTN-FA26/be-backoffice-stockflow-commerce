package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.FileTransfers;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.order.api.ReturnRequestLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Goods returned are taken off the order's invoices ({@link EInvoiceCorrections#drawUpForReturn}; review WF05-einv,
 * findings L2 and L3). The repository is mocked: the subject is which invoices are locked and how, and how much each
 * is still taken to hold. Which adjustment lines count as pieces moved is the repository's query, and is pinned
 * against the database in {@code EInvoiceIntegrationTest}.
 *
 * <p>The goods: cartons of 10 at 20,000 a carton, taxed 10% - an invoice of 10 cartons holds 100 pieces.</p>
 */
class EInvoiceCorrectionsReturnTest {

    private static final Instant RESOLVED_AT = Instant.parse("2026-11-13T03:00:00Z");
    private static final String SKU = "CUP12-0398765432-01";
    private static final BigDecimal CARTON_PRICE = new BigDecimal("20000");
    private static final BigDecimal TEN = new BigDecimal("10");

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final EInvoiceCorrections corrections = new EInvoiceCorrections(invoices, mock(ReturnRequestLookup.class),
            mock(EInvoiceAgreements.class), mock(FileTransfers.class), Clock.fixed(RESOLVED_AT, ZoneOffset.UTC));

    @BeforeEach
    void aRepositoryThatKeepsWhatItIsGiven() {
        when(invoices.nextCode(any())).thenReturn("EINV-20261113-0001");
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(invoices.correctionsOf(any())).thenReturn(EInvoice.Corrections.NONE);
        // A fresh map each time: the service keeps count in the one it is given.
        when(invoices.piecesTakenOff(any())).thenAnswer(call -> new HashMap<UUID, Long>());
    }

    /** An issued original of 10 cartons of 10 - 100 pieces - of {@code orderLineId}, readable only under its lock. */
    private EInvoice issued(UUID orderLineId, int number) {
        EInvoice invoice = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-%04d".formatted(number),
                EInvoiceFixtures.delivery(), EInvoiceFixtures.SELLER, EInvoiceFixtures.BUYER,
                List.of(EInvoiceLine.goods(UUID.randomUUID(), 1, orderLineId, SKU, "Ly giay 12oz", "Thung", 10, 10,
                        CARTON_PRICE, TEN)), false, false);
        EInvoiceFixtures.issued(invoice, number);
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private static ReturnResolved returned(UUID orderId, List<ReturnResolved.Line> lines) {
        return new ReturnResolved(UUID.randomUUID(), "RMA-20261113-0001", orderId, "SO-20261112-0001",
                UUID.randomUUID(), null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", true, lines,
                UUID.randomUUID(), RESOLVED_AT);
    }

    /** {@code pieces} of an order line accepted back and refunded. */
    private static ReturnResolved.Line refunded(UUID orderLineId, int pieces) {
        return new ReturnResolved.Line(orderLineId, SKU, pieces, 0, pieces, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("review L3: every candidate invoice is locked once, in id order, and read only under its lock")
    void aReturnLocksEachInvoiceOnceInIdOrder() {
        UUID order = UUID.randomUUID();
        List<EInvoice> held = List.of(issued(UUID.randomUUID(), 1), issued(UUID.randomUUID(), 2),
                issued(UUID.randomUUID(), 3));
        List<UUID> inIdOrder = held.stream().map(EInvoice::id).sorted().toList();
        // The repository lists them oldest first, which is not id order: here, exactly the other way round.
        List<UUID> oldestFirst = inIdOrder.reversed();
        when(invoices.findInForceOfOrder(order)).thenReturn(oldestFirst);

        EInvoiceCorrections.ReturnAdjustments drawn = corrections.drawUpForReturn(returned(order,
                held.stream().map(invoice -> refunded(invoice.lines().getFirst().orderLineId(), 10)).toList()));

        InOrder locks = inOrder(invoices);
        for (UUID id : inIdOrder) {
            locks.verify(invoices).findByIdForUpdate(id);
        }
        verify(invoices, times(inIdOrder.size())).findByIdForUpdate(any());
        // A read without the lock is how the invoice was once looked at before it was locked, and locked twice.
        verify(invoices, never()).findById(any());
        assertThat(drawn.drafts()).as("walked oldest first, each from the copy its lock returned")
                .extracting(EInvoice.Snapshot::originalEInvoiceId).containsExactlyElementsOf(oldestFirst);
        assertThat(drawn.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("review L2: an invoice holds what it invoiced less what its adjustments moved - pieces an error's "
            + "adjustment added can be returned, pieces it took off cannot be returned again")
    void aReturnCountsWhatAnErrorAdjustmentMoved() {
        // 100 pieces invoiced, and an error's adjustment of +20 (2 cartons, 44,000 with tax): 120 held.
        UUID orderRaised = UUID.randomUUID();
        UUID lineRaised = UUID.randomUUID();
        EInvoice raised = issued(lineRaised, 1);
        when(invoices.findInForceOfOrder(orderRaised)).thenReturn(List.of(raised.id()));
        when(invoices.correctionsOf(raised.id()))
                .thenReturn(new EInvoice.Corrections(true, false, new BigDecimal("44000")));
        when(invoices.piecesTakenOff(raised.id())).thenAnswer(call -> new HashMap<>(Map.of(lineRaised, -20L)));
        // 100 pieces invoiced, and an error's adjustment of -20: 80 held.
        UUID orderLowered = UUID.randomUUID();
        UUID lineLowered = UUID.randomUUID();
        EInvoice lowered = issued(lineLowered, 2);
        when(invoices.findInForceOfOrder(orderLowered)).thenReturn(List.of(lowered.id()));
        when(invoices.correctionsOf(lowered.id()))
                .thenReturn(new EInvoice.Corrections(true, false, new BigDecimal("-44000")));
        when(invoices.piecesTakenOff(lowered.id())).thenAnswer(call -> new HashMap<>(Map.of(lineLowered, 20L)));

        EInvoiceCorrections.ReturnAdjustments ofRaised = corrections.drawUpForReturn(returned(orderRaised,
                List.of(refunded(lineRaised, 110))));
        EInvoiceCorrections.ReturnAdjustments ofLowered = corrections.drawUpForReturn(returned(orderLowered,
                List.of(refunded(lineLowered, 90))));

        assertThat(ofRaised.drafts()).hasSize(1);
        assertThat(ofRaised.drafts().getFirst().lines()).as("110 returned of the 120 held: all of them")
                .extracting(EInvoiceLine::pieces).containsExactly(-110L);
        assertThat(ofRaised.unplaced()).isEmpty();
        assertThat(ofLowered.drafts()).hasSize(1);
        assertThat(ofLowered.drafts().getFirst().lines()).as("90 returned of the 80 held: 80")
                .extracting(EInvoiceLine::pieces).containsExactly(-80L);
        assertThat(ofLowered.unplaced()).as("the rest is the accountant's").isEqualTo(Map.of(lineLowered, 10L));
    }
}
