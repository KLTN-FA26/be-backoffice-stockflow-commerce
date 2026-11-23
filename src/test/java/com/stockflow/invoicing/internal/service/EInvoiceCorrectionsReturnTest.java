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
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("WF-05 TH-1 (17 §4.10 step 40): a return against shipment 2 is taken off shipment 2's invoice, though "
            + "shipment 1's is older and invoiced the same line; a return naming no shipment, the oldest first")
    void aReturnIsTakenOffItsShipmentsInvoiceFirst() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        EInvoice first = issued(line, 1);
        EInvoice second = issued(line, 2);
        when(invoices.findInForceOfOrder(order)).thenReturn(List.of(first.id(), second.id()));
        when(invoices.findLiveOriginalOfShipment(second.shipmentId())).thenReturn(Optional.of(second));
        ReturnResolved plain = returned(order, List.of(refunded(line, 30)));
        ReturnResolved ofSecond = new ReturnResolved(plain.returnRequestId(), plain.rmaNumber(), order,
                plain.orderNumber(), plain.customerId(), null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "VND", true, plain.lines(), plain.resolvedBy(), RESOLVED_AT, null, second.shipmentId());

        assertThat(corrections.drawUpForReturn(ofSecond).drafts()).singleElement()
                .satisfies(draft -> assertThat(draft.originalEInvoiceId()).isEqualTo(second.id()));
        assertThat(corrections.drawUpForReturn(returned(order, List.of(refunded(line, 30)))).drafts()).singleElement()
                .satisfies(draft -> assertThat(draft.originalEInvoiceId()).isEqualTo(first.id()));
    }

    /** The return of {@code lines} against {@code shipmentId}. */
    private static ReturnResolved returnedFrom(UUID orderId, UUID shipmentId, List<ReturnResolved.Line> lines) {
        ReturnResolved plain = returned(orderId, lines);
        return new ReturnResolved(plain.returnRequestId(), plain.rmaNumber(), orderId, plain.orderNumber(),
                plain.customerId(), null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", true, lines,
                plain.resolvedBy(), RESOLVED_AT, null, shipmentId);
    }

    @Test
    @DisplayName("WF-05 BE-44 (G4): units refunded worth no money - an order with nothing paid (TH-2), the flag set "
            + "by units - are still taken off the invoice at its own price: the invoice said they were sold")
    void unitsRefundedWorthNoMoneyStillAdjustTheInvoice() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        EInvoice own = issued(line, 1);
        when(invoices.findLiveOriginalOfShipment(own.shipmentId())).thenReturn(Optional.of(own));
        ReturnResolved nothingPaid = returnedFrom(order, own.shipmentId(), List.of(refunded(line, 20)));
        assertThat(nothingPaid.offsetAmount().add(nothingPaid.refundedAmount())).as("no money moved").isZero();
        assertThat(nothingPaid.lines().getFirst().creditAmount()).isZero();

        EInvoiceCorrections.ReturnAdjustments drawn = corrections.drawUpForReturn(nothingPaid);

        assertThat(drawn.drafts()).singleElement().satisfies(draft -> {
            assertThat(draft.lines()).extracting(EInvoiceLine::quantity).containsExactly(-2);
            // 2 cartons at 20,000 with 10% VAT, whatever the return was settled for in money.
            assertThat(draft.grandTotal()).isEqualByComparingTo("-44000");
        });
        assertThat(drawn.pending()).isEmpty();
        assertThat(drawn.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("WF-05 BE-28 (HĐ-4): more returned than the shipment's own invoice holds - the rest is the "
            + "accountant's, never taken off another shipment's invoice")
    void aReturnNeverSpillsOntoAnotherShipment() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        EInvoice other = issued(line, 1);
        EInvoice own = issued(line, 2);
        when(invoices.findInForceOfOrder(order)).thenReturn(List.of(other.id(), own.id()));
        when(invoices.findLiveOriginalOfShipment(own.shipmentId())).thenReturn(Optional.of(own));

        EInvoiceCorrections.ReturnAdjustments drawn = corrections.drawUpForReturn(returnedFrom(order,
                own.shipmentId(), List.of(refunded(line, 130))));

        assertThat(drawn.drafts()).singleElement().satisfies(draft -> {
            assertThat(draft.originalEInvoiceId()).isEqualTo(own.id());
            assertThat(draft.lines()).extracting(EInvoiceLine::pieces).containsExactly(-100L);
        });
        assertThat(drawn.unplaced()).isEqualTo(Map.of(line, 30L));
        verify(invoices, never()).findByIdForUpdate(other.id());
    }

    @Test
    @DisplayName("WF-05 BE-28: the shipment's own invoice not issued yet - the pieces are kept and nothing is taken "
            + "off another; once it is issued they are taken off it, once")
    void aReturnWaitsForItsOwnInvoice() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        EInvoice other = issued(line, 1);
        EInvoice own = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0009", EInvoiceFixtures.delivery(),
                EInvoiceFixtures.SELLER, EInvoiceFixtures.BUYER, List.of(EInvoiceLine.goods(UUID.randomUUID(), 1, line,
                        SKU, "Ly giay 12oz", "Thung", 10, 10, CARTON_PRICE, TEN)), false, false);
        when(invoices.findInForceOfOrder(order)).thenReturn(List.of(other.id()));
        when(invoices.findLiveOriginalOfShipment(own.shipmentId())).thenReturn(Optional.of(own));
        ReturnResolved resolved = returnedFrom(order, own.shipmentId(), List.of(refunded(line, 30)));

        EInvoiceCorrections.ReturnAdjustments kept = corrections.drawUpForReturn(resolved);

        assertThat(kept.drafts()).isEmpty();
        assertThat(kept.pending()).isEqualTo(Map.of(line, 30L));
        org.mockito.ArgumentCaptor<EInvoiceRepository.PendingReturnPieces> pieces =
                org.mockito.ArgumentCaptor.forClass(EInvoiceRepository.PendingReturnPieces.class);
        verify(invoices).keepPendingReturn(pieces.capture(), eq(RESOLVED_AT));
        assertThat(pieces.getValue().shipmentId()).isEqualTo(own.shipmentId());
        assertThat(pieces.getValue().pieces()).isEqualTo(30L);
        verify(invoices, never()).findByIdForUpdate(other.id());

        // Issued now: the kept pieces come off it.
        EInvoiceFixtures.issued(own, 9);
        when(invoices.findByIdForUpdate(own.id())).thenReturn(Optional.of(own));
        when(invoices.findPendingReturnsOfShipment(own.shipmentId())).thenReturn(List.of(pieces.getValue()));
        var placed = corrections.drawUpPendingReturns(own.shipmentId());
        assertThat(placed).hasSize(1);
        assertThat(placed.values().iterator().next().drafts()).singleElement().satisfies(draft -> {
            assertThat(draft.originalEInvoiceId()).isEqualTo(own.id());
            assertThat(draft.returnRequestId()).isEqualTo(resolved.returnRequestId());
            assertThat(draft.lines()).extracting(EInvoiceLine::pieces).containsExactly(-30L);
        });
        verify(invoices).settlePendingReturn(resolved.returnRequestId(), own.shipmentId(), RESOLVED_AT);

        // Adjusted meanwhile (by hand, or a second delivery of the event): nothing drawn twice.
        when(invoices.hasAdjustmentForReturn(resolved.returnRequestId())).thenReturn(true);
        assertThat(corrections.drawUpPendingReturns(own.shipmentId())).isEmpty();
    }

    @Test
    @DisplayName("WF-05 Q-9: the goods are on a replacement, which no provider adjusts - reported for the portal, "
            + "nothing drawn up, the pieces kept for the record of the reduction made on the portal")
    void aReturnOnAReplacementIsReported() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        EInvoice original = issued(line, 1);
        EInvoice replacement = EInvoice.drawUpReplacement(UUID.randomUUID(), "EINV-20261113-0002", original, "sai",
                original.buyer(), original.lines(), EInvoiceFixtures.CORRECTED_ON, EInvoice.Corrections.NONE);
        EInvoiceFixtures.issued(EInvoiceFixtures.agreed(replacement), 2);
        when(invoices.findByIdForUpdate(replacement.id())).thenReturn(Optional.of(replacement));
        when(invoices.findLiveOriginalOfShipment(original.shipmentId())).thenReturn(Optional.of(replacement));

        ReturnResolved drawnFor = returnedFrom(order, original.shipmentId(), List.of(refunded(line, 20)));
        EInvoiceCorrections.ReturnAdjustments drawn = corrections.drawUpForReturn(drawnFor);

        assertThat(drawn.drafts()).isEmpty();
        assertThat(drawn.onReplacement()).isEqualTo(Map.of(line, 20L));
        verify(invoices).keepPendingReturn(eq(new EInvoiceRepository.PendingReturnPieces(
                drawnFor.returnRequestId(), drawnFor.rmaNumber(), order, drawnFor.orderNumber(), original.shipmentId(),
                line, 20)), any());
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
