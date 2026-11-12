package com.stockflow.invoicing.internal.service;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShippingFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What goes on the draft of a delivered shipment (plan §1.4): cartons at the carton's price with the VAT rate of the day
 * of delivery, the plate fees on the order's first invoice, the shipment's share of the shipping fee, the seller and the
 * buyer copied - and the accountant's corrections. The ports are mocked: the arithmetic and the rules are the subject.
 */
class EInvoiceDraftsTest {

    private static final Instant DELIVERED_AT = Instant.parse("2026-11-12T03:00:00Z");
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID LINE_CUP = UUID.randomUUID();
    private static final UUID LINE_LID = UUID.randomUUID();
    private static final String CUP = "CUP12-0398765432-01";
    private static final String LID = "LID90-0398765432-01";
    private static final BigDecimal TEN = new BigDecimal("10.00");

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final DeliveredShipments shipments = mock(DeliveredShipments.class);
    private final CustomerLegalProfiles legalProfiles = mock(CustomerLegalProfiles.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final SellerProperties seller = new SellerProperties("CONG TY TNHH BAO BI", "0312345678",
            "1 Le Loi, P. Ben Nghe, TP HCM", "Nguyen Van A", "Giam doc");
    private final Clock clock = Clock.fixed(DELIVERED_AT.plusSeconds(60), ZoneOffset.UTC);
    private EInvoiceDrafts drafts;
    /** The invoices "in the database": what a later draft of the same order sees. */
    private final List<EInvoice> saved = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        drafts = new EInvoiceDrafts(invoices, shipments, legalProfiles, customers, seller, clock);
        when(invoices.findLiveOriginalOfShipment(any())).thenReturn(Optional.empty());
        when(invoices.nextCode(any())).thenReturn("EINV-20261112-0001");
        when(invoices.save(any())).thenAnswer(call -> {
            EInvoice invoice = call.getArgument(0);
            saved.removeIf(other -> other.id().equals(invoice.id()));
            saved.add(invoice);
            return invoice;
        });
        // What the database answers from the order's originals that were not voided (EInvoiceRepositoryAdapter).
        when(invoices.invoicedOfOrder(any())).thenAnswer(call -> {
            long units = 0;
            BigDecimal shipping = BigDecimal.ZERO;
            java.util.Set<String> plateSkus = new java.util.HashSet<>();
            for (EInvoice invoice : saved) {
                if (!invoice.orderId().equals(call.getArgument(0)) || invoice.status() == EInvoiceStatus.VOIDED_DRAFT) {
                    continue;
                }
                for (EInvoiceLine line : invoice.lines()) {
                    if (line.kind() == EInvoiceLine.Kind.GOODS) {
                        units += (long) line.quantity() * (line.unitsPerCarton() == null ? 1 : line.unitsPerCarton());
                    } else if (line.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE) {
                        shipping = shipping.add(line.amount());
                    } else {
                        plateSkus.add(line.sku());
                    }
                }
            }
            return new FeeAllocation.Invoiced(units, shipping, plateSkus);
        });
        when(legalProfiles.legalProfile(CUSTOMER)).thenReturn(Optional.of(new CustomerLegalProfile(CUSTOMER,
                "Cong ty Tra Sua", "0398765432", "12 Nguyen Hue, TP HCM", "Tran Van B", "Giam doc",
                "ke.toan@trasua.example.com", null, 3)));
    }

    /**
     * An order of 40 cartons of cups (50 a carton, 2,000 a piece) and 10 cartons of lids (100 a carton, 500 a piece),
     * a plate fee of 500,000 for the cups and a shipping fee of 100,000 - 3,000 pieces in all; this shipment carries
     * {@code cupCartons} and {@code lidCartons}.
     */
    private DeliveredShipment shipment(UUID shipmentId, int cupCartons, int lidCartons) {
        return shipment(shipmentId, cupCartons, lidCartons, 3000, DELIVERED_AT);
    }

    /** The same, of an order that means to deliver {@code orderUnits} pieces, delivered when the carrier says. */
    private DeliveredShipment shipment(UUID shipmentId, int cupCartons, int lidCartons, long orderUnits,
                                       Instant deliveredAt) {
        List<DeliveredShipment.Line> lines = new java.util.ArrayList<>();
        if (cupCartons > 0) {
            lines.add(new DeliveredShipment.Line(LINE_CUP, CUP, "Ly giay 12oz", cupCartons, 50, cupCartons * 50,
                    new BigDecimal("2000"), TEN, TEN));
        }
        if (lidCartons > 0) {
            lines.add(new DeliveredShipment.Line(LINE_LID, LID, "Nap 90mm", lidCartons, 100, lidCartons * 100,
                    new BigDecimal("500"), TEN, TEN));
        }
        return new DeliveredShipment(ORDER, "SO-20261101-0007", CUSTOMER, "HD-20261101-0007", shipmentId,
                "SO-20261101-0007-S1", null, deliveredAt, "VND", lines,
                List.of(new DeliveredShipment.Fee(FeeKind.PLATE_FEE, CUP, new BigDecimal("500000"), TEN, TEN),
                        new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, new BigDecimal("100000"), TEN, TEN)),
                orderUnits, cupCartons * 50L + lidCartons * 100L);
    }

    private EInvoice.Snapshot drawUp(DeliveredShipment delivered) {
        when(shipments.find(ORDER, delivered.shipmentId())).thenReturn(Optional.of(delivered));
        EInvoiceDrafts.DrawnUp drawn = drafts.drawUp(ORDER, delivered.shipmentId());
        assertThat(drawn.created()).isTrue();
        return drawn.invoice();
    }

    @Test
    @DisplayName("the first delivery: cartons x the carton price, the plate fee whole, its share of the shipping fee")
    void theFirstDelivery() {
        EInvoice.Snapshot invoice = drawUp(shipment(UUID.randomUUID(), 20, 5));
        verify(invoices).lockOrder(ORDER);

        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(invoice.orderNumber()).isEqualTo("SO-20261101-0007");
        assertThat(invoice.contractNumber()).isEqualTo("HD-20261101-0007");
        assertThat(invoice.lines()).extracting(EInvoiceLine::lineNo, EInvoiceLine::kind, EInvoiceLine::sku,
                EInvoiceLine::unit, EInvoiceLine::quantity, EInvoiceLine::unitsPerCarton,
                line -> line.unitPrice().toPlainString(), line -> line.amount().toPlainString(),
                line -> line.taxAmount().toPlainString()).containsExactly(
                // 20 cartons x (50 x 2,000) and 5 cartons x (100 x 500)
                tuple(1, EInvoiceLine.Kind.GOODS, CUP, "Thùng", 20, 50, "100000", "2000000", "200000"),
                tuple(2, EInvoiceLine.Kind.GOODS, LID, "Thùng", 5, 100, "50000", "250000", "25000"),
                // the plate fee whole; 1,500 of the order's 3,000 pieces -> half of the shipping fee
                tuple(3, EInvoiceLine.Kind.FEE, CUP, "Lần", 1, null, "500000", "500000", "50000"),
                tuple(4, EInvoiceLine.Kind.FEE, null, "Lần", 1, null, "50000", "50000", "5000"));
        assertThat(invoice.lines().get(2).feeKind()).isEqualTo(EInvoiceLine.FeeKind.PLATE_FEE);
        assertThat(invoice.lines().get(2).itemName()).isEqualTo("Phí khuôn / bản in (" + CUP + ")");
        assertThat(invoice.lines().get(3).feeKind()).isEqualTo(EInvoiceLine.FeeKind.SHIPPING_FEE);
        assertThat(invoice.lines().get(3).itemName()).isEqualTo("Phí vận chuyển (lần giao SO-20261101-0007-S1)");
        assertThat(invoice.subtotal()).isEqualByComparingTo("2800000");
        assertThat(invoice.taxTotal()).isEqualByComparingTo("280000");
        assertThat(invoice.grandTotal()).isEqualByComparingTo("3080000");
        assertThat(invoice.amountInWords()).isEqualTo("Ba triệu không trăm tám mươi nghìn đồng");
    }

    @Test
    @DisplayName("a later delivery carries no plate fee, and the shipping shares of all deliveries add up to the fee")
    void laterDeliveries() {
        EInvoice.Snapshot first = drawUp(shipment(UUID.randomUUID(), 13, 0));    // 650 pieces
        EInvoice.Snapshot second = drawUp(shipment(UUID.randomUUID(), 13, 3));   // 950 pieces
        EInvoice.Snapshot third = drawUp(shipment(UUID.randomUUID(), 14, 7));    // 1,400 pieces

        assertThat(first.lines()).extracting(EInvoiceLine::feeKind).containsExactly(null,
                EInvoiceLine.FeeKind.PLATE_FEE, EInvoiceLine.FeeKind.SHIPPING_FEE);
        assertThat(second.lines()).extracting(EInvoiceLine::feeKind).as("no plate fee after the first delivery")
                .containsExactly(null, null, EInvoiceLine.FeeKind.SHIPPING_FEE);
        assertThat(third.lines()).extracting(EInvoiceLine::feeKind).containsExactly(null, null,
                EInvoiceLine.FeeKind.SHIPPING_FEE);
        BigDecimal shipping = BigDecimal.ZERO;
        for (EInvoice.Snapshot invoice : List.of(first, second, third)) {
            shipping = shipping.add(invoice.lines().getLast().amount());
        }
        // 650, 950 and 1,400 of 3,000 pieces: 21,667 + 31,666 + 46,667
        assertThat(first.lines().getLast().amount()).isEqualByComparingTo("21667");
        assertThat(second.lines().getLast().amount()).isEqualByComparingTo("31666");
        assertThat(third.lines().getLast().amount()).isEqualByComparingTo("46667");
        assertThat(shipping).as("the order's shipping fee, to the dong").isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("review finding 1: deliveries recorded against the carrier's clock - the plate fee once, the shipping "
            + "fee once")
    void deliveriesRecordedOutOfTimeOrder() {
        // An order of 1,000 pieces. Shipment A (400) is recorded first, delivered 10/11 09:00. Shipment B (600) is
        // recorded afterwards from a carrier report that puts its delivery at 09/11 16:00 - before A's.
        EInvoice.Snapshot a = drawUp(shipment(UUID.randomUUID(), 8, 0, 1000, Instant.parse("2026-11-10T02:00:00Z")));
        EInvoice.Snapshot b = drawUp(shipment(UUID.randomUUID(), 12, 0, 1000, Instant.parse("2026-11-09T09:00:00Z")));

        assertThat(a.lines()).extracting(EInvoiceLine::feeKind, line -> line.amount().toPlainString())
                .containsExactly(tuple(null, "800000"), tuple(EInvoiceLine.FeeKind.PLATE_FEE, "500000"),
                        tuple(EInvoiceLine.FeeKind.SHIPPING_FEE, "40000"));
        // B is the earlier delivery by the carrier's clock and the second invoice: no plate fee again, and the rest
        // of the shipping fee - not a share measured from "nothing delivered before".
        assertThat(b.lines()).extracting(EInvoiceLine::feeKind, line -> line.amount().toPlainString())
                .containsExactly(tuple(null, "1200000"), tuple(EInvoiceLine.FeeKind.SHIPPING_FEE, "60000"));
        assertThat(b.issueDate()).as("each invoice is still dated its own delivery")
                .isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(a.subtotal().add(b.subtotal())).as("goods 2,000,000 + plate 500,000 + shipping 100,000")
                .isEqualByComparingTo("2600000");

        // Three deliveries recorded in an order unlike their times: the shares still add up to the fee.
        saved.clear();
        BigDecimal shipping = BigDecimal.ZERO;
        int plateFees = 0;
        Instant[] carrierTimes = {Instant.parse("2026-11-12T02:00:00Z"), Instant.parse("2026-11-10T02:00:00Z"),
                Instant.parse("2026-11-11T02:00:00Z")};
        int[] cartons = {7, 6, 7};
        for (int i = 0; i < 3; i++) {
            EInvoice.Snapshot invoice = drawUp(shipment(UUID.randomUUID(), cartons[i], 0, 1000, carrierTimes[i]));
            for (EInvoiceLine line : invoice.lines()) {
                if (line.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE) {
                    shipping = shipping.add(line.amount());
                } else if (line.feeKind() == EInvoiceLine.FeeKind.PLATE_FEE) {
                    plateFees++;
                }
            }
        }
        assertThat(plateFees).isEqualTo(1);
        assertThat(shipping).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("review finding 2: part of the order closed between two deliveries - the invoices still carry the fee")
    void aClosureBetweenDeliveries() {
        // 5,000 pieces ordered; 40 cartons (2,000) delivered: 40,000 of the 100,000 shipping fee.
        EInvoice.Snapshot first = drawUp(shipment(UUID.randomUUID(), 40, 0, 5000, DELIVERED_AT));
        assertThat(first.lines().getLast().amount()).isEqualByComparingTo("40000");
        // 1,000 pieces are closed: the order now means to deliver 4,000, and the next 2,000 complete it.
        EInvoice.Snapshot second = drawUp(shipment(UUID.randomUUID(), 40, 0, 4000, DELIVERED_AT));
        assertThat(second.lines().getLast().feeKind()).isEqualTo(EInvoiceLine.FeeKind.SHIPPING_FEE);
        assertThat(second.lines().getLast().amount()).as("60,000 - not the 50,000 of a fee re-measured against 4,000")
                .isEqualByComparingTo("60000");
        assertThat(second.lines().getLast().taxAmount()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("a voided draft's fees are free again: the invoice drawn up in its place carries them")
    void aVoidedDraftGivesItsFeesBack() {
        UUID shipmentId = UUID.randomUUID();
        EInvoice.Snapshot draft = drawUp(shipment(shipmentId, 20, 5));
        EInvoice voided = saved.getFirst();
        voided.voidDraft("drawn up by mistake", null, DELIVERED_AT);

        EInvoice.Snapshot again = drawUp(shipment(shipmentId, 20, 5));

        assertThat(again.id()).isNotEqualTo(draft.id());
        assertThat(again.lines()).extracting(EInvoiceLine::feeKind, line -> line.amount().toPlainString())
                .containsExactly(tuple(null, "2000000"), tuple(null, "250000"),
                        tuple(EInvoiceLine.FeeKind.PLATE_FEE, "500000"), tuple(EInvoiceLine.FeeKind.SHIPPING_FEE, "50000"));
    }

    @Test
    @DisplayName("review finding 4: a line whose order fixed no VAT rate is flagged, whatever rate was looked up")
    void aLineWithoutVatOnTheOrderIsFlagged() {
        UUID shipmentId = UUID.randomUUID();
        DeliveredShipment delivered = new DeliveredShipment(ORDER, "SO-20261101-0007", CUSTOMER, null, shipmentId,
                null, null, DELIVERED_AT, "VND",
                List.of(new DeliveredShipment.Line(LINE_CUP, CUP, "Ly giay 12oz", 10, 50, 500,
                        new BigDecimal("2000"), TEN, null)), List.of(), 500, 500);

        EInvoice.Snapshot invoice = drawUp(delivered);

        assertThat(invoice.lines().getFirst().taxAmount()).as("the day's rate is on the draft: 1,000,000 at 10%")
                .isEqualByComparingTo("100000");
        assertThat(invoice.warnings()).as("and the draft is not issued without the accountant")
                .containsExactly(EInvoice.Warning.TAX_RATE_NOT_ON_CONTRACT);

        // A fee without a rate on the order is flagged the same way.
        saved.clear();
        DeliveredShipment feeOnly = new DeliveredShipment(ORDER, "SO-20261101-0007", CUSTOMER, null,
                UUID.randomUUID(), null, null, DELIVERED_AT, "VND", shipment(shipmentId, 1, 0).lines(),
                List.of(new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, new BigDecimal("100000"), TEN, null)),
                50, 50);
        assertThat(drawUp(feeOnly).warnings()).containsExactly(EInvoice.Warning.TAX_RATE_NOT_ON_CONTRACT);
    }

    @Test
    @DisplayName("review finding 7: a shipment whose invoice was replaced gets no second original (E4 decides)")
    void aReplacedOriginalIsNotDrawnUpAgain() {
        UUID shipmentId = UUID.randomUUID();
        when(invoices.hasReplacedOriginalOfShipment(shipmentId)).thenReturn(true);

        assertThatThrownBy(() -> drafts.drawUp(ORDER, shipmentId)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_NOT_INVOICEABLE);
        verify(shipments, never()).find(any(), any());
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("the rate of the day of delivery is the invoice's; where it is not the contract's, the draft is flagged")
    void theRateOfTheDay() {
        UUID shipmentId = UUID.randomUUID();
        BigDecimal eight = new BigDecimal("8.00");
        DeliveredShipment delivered = new DeliveredShipment(ORDER, "SO-20261101-0007", CUSTOMER, null, shipmentId,
                null, null, DELIVERED_AT, "VND",
                List.of(new DeliveredShipment.Line(LINE_CUP, CUP, "Ly giay 12oz", 10, 50, 500,
                        new BigDecimal("2000"), eight, TEN)),
                List.of(new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, new BigDecimal("100000"), eight, TEN)),
                500, 500);

        EInvoice.Snapshot invoice = drawUp(delivered);

        assertThat(invoice.lines()).extracting(line -> line.taxRate().toPlainString(),
                line -> line.taxAmount().toPlainString()).containsExactly(tuple("8.00", "80000"),
                tuple("8.00", "8000"));
        assertThat(invoice.lines().getLast().itemName()).as("no shipment code to name").isEqualTo("Phí vận chuyển");
        assertThat(invoice.warnings()).containsExactly(EInvoice.Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);
    }

    @Test
    @DisplayName("a line not ordered in cartons is counted in pieces at the piece's price")
    void piecesWhenNotInCartons() {
        UUID shipmentId = UUID.randomUUID();
        DeliveredShipment delivered = new DeliveredShipment(ORDER, "SO-20261101-0007", CUSTOMER, null, shipmentId,
                null, null, DELIVERED_AT, "VND",
                List.of(new DeliveredShipment.Line(LINE_CUP, CUP, "Ly giay 12oz", null, null, 30,
                        new BigDecimal("2000"), TEN, TEN)), List.of(), 30, 30);

        EInvoiceLine line = drawUp(delivered).lines().getFirst();

        assertThat(line.unit()).isEqualTo("Cái");
        assertThat(line.quantity()).isEqualTo(30);
        assertThat(line.unitsPerCarton()).isNull();
        assertThat(line.amount()).isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("the seller and the buyer are copied: the buyer from its legal profile, with its invoice e-mail")
    void thePartiesAreCopied() {
        EInvoice.Snapshot invoice = drawUp(shipment(UUID.randomUUID(), 20, 5));

        assertThat(invoice.seller().name()).isEqualTo("CONG TY TNHH BAO BI");
        assertThat(invoice.seller().taxCode()).isEqualTo("0312345678");
        assertThat(invoice.seller().address()).isEqualTo("1 Le Loi, P. Ben Nghe, TP HCM");
        assertThat(invoice.buyer().name()).isEqualTo("Cong ty Tra Sua");
        assertThat(invoice.buyer().taxCode()).isEqualTo("0398765432");
        assertThat(invoice.buyer().address()).isEqualTo("12 Nguyen Hue, TP HCM");
        assertThat(invoice.buyer().email()).isEqualTo("ke.toan@trasua.example.com");
        assertThat(invoice.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a customer without a legal profile still gets its draft - from its record, without an address, flagged")
    void aBuyerWithoutLegalProfile() {
        when(legalProfiles.legalProfile(CUSTOMER)).thenReturn(Optional.empty());
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(new CustomerSummary(CUSTOMER, null, "Cong ty Tra Sua",
                "lien.he@trasua.example.com", null, null, null, "ACTIVE", 0, DELIVERED_AT)));

        EInvoice.Snapshot invoice = drawUp(shipment(UUID.randomUUID(), 20, 5));

        assertThat(invoice.buyer().name()).isEqualTo("Cong ty Tra Sua");
        assertThat(invoice.buyer().taxCode()).isNull();
        assertThat(invoice.buyer().address()).isNull();
        assertThat(invoice.buyer().email()).as("the contact e-mail: the invoice e-mail's default")
                .isEqualTo("lien.he@trasua.example.com");
        assertThat(invoice.warnings()).containsExactly(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);
    }

    @Test
    @DisplayName("21 BR-01: a shipment that has its invoice gets no second one - the one in force is returned")
    void oneInvoicePerShipment() {
        EInvoice inForce = EInvoiceFixtures.draft();
        UUID shipmentId = inForce.shipmentId();
        when(invoices.findLiveOriginalOfShipment(shipmentId)).thenReturn(Optional.of(inForce));

        EInvoiceDrafts.DrawnUp drawn = drafts.drawUp(inForce.orderId(), shipmentId);

        assertThat(drawn.created()).isFalse();
        assertThat(drawn.invoice().id()).isEqualTo(inForce.id());
        verify(shipments, never()).find(any(), any());
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("nothing is drawn up for a shipment that is not delivered, in another currency, or without a seller")
    void notInvoiceable() {
        UUID shipmentId = UUID.randomUUID();
        when(shipments.find(ORDER, shipmentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> drafts.drawUp(ORDER, shipmentId)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_NOT_INVOICEABLE);

        DeliveredShipment dollars = new DeliveredShipment(ORDER, "SO-1", CUSTOMER, null, shipmentId, null, null,
                DELIVERED_AT, "USD", shipment(shipmentId, 1, 0).lines(), List.of(), 50, 50);
        when(shipments.find(ORDER, shipmentId)).thenReturn(Optional.of(dollars));
        assertThatThrownBy(() -> drafts.drawUp(ORDER, shipmentId)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_NOT_INVOICEABLE);

        DeliveredShipment nothing = new DeliveredShipment(ORDER, "SO-1", CUSTOMER, null, shipmentId, null, null,
                DELIVERED_AT, "VND", List.of(), shipment(shipmentId, 1, 0).fees(), 50, 0);
        when(shipments.find(ORDER, shipmentId)).thenReturn(Optional.of(nothing));
        assertThatThrownBy(() -> drafts.drawUp(ORDER, shipmentId)).as("fees alone are not a delivery")
                .extracting(EInvoiceDraftsTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_INVOICEABLE);

        when(shipments.find(ORDER, shipmentId)).thenReturn(Optional.of(shipment(shipmentId, 1, 0)));
        EInvoiceDrafts noSeller = new EInvoiceDrafts(invoices, shipments, legalProfiles, customers,
                new SellerProperties("CONG TY", " ", "1 Le Loi", "A", "Giam doc"), clock);
        assertThatThrownBy(() -> noSeller.drawUp(ORDER, shipmentId)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.SELLER_PROFILE_UNAVAILABLE);
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("E0 L6: a delivery without a delivery time is refused with its own code, never dated today")
    void aDeliveryWithoutATimeIsRefused() {
        UUID shipmentId = UUID.randomUUID();
        when(shipments.find(ORDER, shipmentId)).thenReturn(Optional.of(shipment(shipmentId, 1, 0, 3000, null)));
        assertThatThrownBy(() -> drafts.drawUp(ORDER, shipmentId)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_DELIVERY_TIME_MISSING);
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("the accountant corrects the buyer and a line's name on a draft, against the version read")
    void revise() {
        EInvoice draft = EInvoiceFixtures.draft();
        UUID line = draft.lines().getFirst().id();
        when(invoices.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));

        EInvoice.Snapshot revised = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), 0, " Cong ty TNHH Tra Sua ",
                "0398 765 432 001", null, " KE.TOAN@TraSua.example.com ", Map.of(line, "Ly giay 12oz in logo")));

        assertThat(revised.buyer().name()).isEqualTo("Cong ty TNHH Tra Sua");
        assertThat(revised.buyer().taxCode()).as("13 digits: a dependent unit's 10-3").isEqualTo("0398765432-001");
        assertThat(revised.buyer().address()).as("left out: unchanged").isEqualTo(EInvoiceFixtures.BUYER.address());
        assertThat(revised.buyer().email()).isEqualTo("ke.toan@trasua.example.com");
        assertThat(revised.lines().getFirst().itemName()).isEqualTo("Ly giay 12oz in logo");
        assertThat(revised.grandTotal()).isEqualByComparingTo(draft.snapshot().grandTotal());

        assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), 5, null, null, null, null, null)))
                .as("a stale version").extracting(EInvoiceDraftsTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        for (String wrong : new String[] {"12345", "03987654321", "0398765432-01", "abcdefghij"}) {
            assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), 0, null, wrong, null, null,
                    null))).as("tax code %s", wrong).extracting(EInvoiceDraftsTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> drafts.revise(new EInvoiceDrafts.Revise(draft.id(), 0, null, null, null, "khong phai",
                null))).extracting(EInvoiceDraftsTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Sent blank: cleared, and the draft waits for it.
        EInvoice.Snapshot cleared = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), 0, null, " ", null, null,
                null));
        assertThat(cleared.buyer().taxCode()).isNull();
        assertThat(cleared.warnings()).containsExactly(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);
    }

    @Test
    @DisplayName("the buyer is copied again from the profile; an invoice that is not a draft is not touched")
    void refreshBuyer() {
        EInvoice draft = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0009",
                new EInvoice.Delivery(ORDER, "SO-1", null, UUID.randomUUID(), null, null, CUSTOMER,
                        LocalDate.of(2026, 11, 12)), EInvoiceFixtures.SELLER,
                new com.stockflow.invoicing.internal.domain.InvoiceParty("Cong ty Tra Sua", null, null, null),
                EInvoiceFixtures.lines(), false, false);
        when(invoices.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));

        EInvoice.Snapshot refreshed = drafts.refreshBuyer(draft.id(), 0);
        assertThat(refreshed.buyer().taxCode()).isEqualTo("0398765432");
        assertThat(refreshed.buyer().email()).isEqualTo("ke.toan@trasua.example.com");
        assertThat(refreshed.warnings()).isEmpty();

        EInvoice issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        when(invoices.findByIdForUpdate(issued.id())).thenReturn(Optional.of(issued));
        when(legalProfiles.legalProfile(issued.customerId())).thenReturn(Optional.of(new CustomerLegalProfile(
                issued.customerId(), "Ten Moi", "0300000000", "Dia chi moi", "A", "B", "moi@example.com", null, 1)));
        assertThatThrownBy(() -> drafts.refreshBuyer(issued.id(), 0)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
        assertThat(issued.buyer()).as("21 BR-06: an issued invoice keeps the buyer it was issued to")
                .isEqualTo(EInvoiceFixtures.BUYER);

        when(invoices.findByIdForUpdate(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> drafts.refreshBuyer(UUID.randomUUID(), 0)).extracting(EInvoiceDraftsTest::code)
                .isEqualTo(ErrorCode.EINVOICE_NOT_FOUND);
    }

    @Test
    @DisplayName("21 §4.5: a delivery corrected to a failure voids the shipment's draft - never an invoice at the provider")
    void aCorrectedDeliveryVoidsTheDraft() {
        UUID noInvoice = UUID.randomUUID();
        assertThat(drafts.voidForCorrectedDelivery(ORDER, noInvoice)).isEqualTo(EInvoiceDrafts.CorrectionOutcome.NO_INVOICE);

        for (EInvoiceStatus unsent : new EInvoiceStatus[] {EInvoiceStatus.DRAFT, EInvoiceStatus.REJECTED}) {
            EInvoice invoice = EInvoiceFixtures.in(unsent);
            when(invoices.findLiveOriginalOfShipment(invoice.shipmentId())).thenReturn(Optional.of(invoice));
            when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));

            assertThat(drafts.voidForCorrectedDelivery(invoice.orderId(), invoice.shipmentId())).as("%s", unsent)
                    .isEqualTo(EInvoiceDrafts.CorrectionOutcome.VOIDED);
            assertThat(invoice.status()).isEqualTo(EInvoiceStatus.VOIDED_DRAFT);
            assertThat(invoice.snapshot().voidedBy()).as("the system's doing").isNull();
            assertThat(invoice.snapshot().voidReason()).contains("corrected to a failure");
        }
        for (EInvoiceStatus sent : new EInvoiceStatus[] {EInvoiceStatus.SUBMITTING, EInvoiceStatus.ISSUED}) {
            EInvoice invoice = EInvoiceFixtures.in(sent);
            when(invoices.findLiveOriginalOfShipment(invoice.shipmentId())).thenReturn(Optional.of(invoice));
            when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));

            assertThat(drafts.voidForCorrectedDelivery(invoice.orderId(), invoice.shipmentId())).as("%s", sent)
                    .isEqualTo(EInvoiceDrafts.CorrectionOutcome.LEFT_FOR_ADJUSTMENT);
            assertThat(invoice.status()).as("it may exist in law: never voided").isEqualTo(sent);
        }
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /** The order's shipping fee of 100,000 whole, the order now meaning to deliver {@code orderUnits} pieces. */
    private OrderShippingFee shippingFee(long orderUnits) {
        return shippingFee(orderUnits, DELIVERED_AT);
    }

    /** The same, its last delivery at {@code lastDeliveredAt} (null: nothing was delivered). */
    private OrderShippingFee shippingFee(long orderUnits, Instant lastDeliveredAt) {
        return new OrderShippingFee(ORDER, "SO-20261101-0007", CUSTOMER, "HD-20261101-0007", "VND",
                new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, new BigDecimal("100000"), TEN, TEN), orderUnits,
                lastDeliveredAt);
    }

    @Test
    @DisplayName("integration item a: the rest closed after every delivery was invoiced - the rest of the shipping fee "
            + "goes on an original of its own, no shipment, one fee line, dated the day of the last delivery (E0 M5); "
            + "drawn up once")
    void theShippingFeeRestOnAnInvoiceOfItsOwn() {
        // 5,000 pieces ordered, 2,000 delivered and invoiced with 40,000 of the fee; the other 3,000 are closed.
        drawUp(shipment(UUID.randomUUID(), 40, 0, 5000, DELIVERED_AT));
        Instant closedAt = DELIVERED_AT.plus(java.time.Duration.ofDays(3));
        when(shipments.shippingFee(ORDER, closedAt)).thenReturn(Optional.of(shippingFee(2000)));
        when(invoices.findLiveShippingFeeInvoice(ORDER)).thenAnswer(call -> saved.stream()
                .filter(invoice -> invoice.shipmentId() == null && invoice.status() != EInvoiceStatus.VOIDED_DRAFT)
                .findFirst());

        EInvoiceDrafts.DrawnUp rest = drafts.drawUpShippingFeeRest(ORDER, closedAt).orElseThrow();

        assertThat(rest.created()).isTrue();
        assertThat(rest.invoice().shipmentId()).isNull();
        assertThat(rest.invoice().issueDate()).as("the carrying ended at the last delivery, not at the closing")
                .isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(rest.invoice().lines()).singleElement().satisfies(line -> {
            assertThat(line.feeKind()).isEqualTo(EInvoiceLine.FeeKind.SHIPPING_FEE);
            assertThat(line.amount()).isEqualByComparingTo("60000");
            assertThat(line.taxAmount()).isEqualByComparingTo("6000");
        });

        EInvoiceDrafts.DrawnUp again = drafts.drawUpShippingFeeRest(ORDER, closedAt).orElseThrow();
        assertThat(again.created()).isFalse();
        assertThat(again.invoice().id()).isEqualTo(rest.invoice().id());
    }

    @Test
    @DisplayName("integration item a: while a delivery is still to be invoiced, closing draws nothing - that delivery "
            + "takes the rest of the fee")
    void aDeliveryStillToBeInvoicedTakesTheRest() {
        // 5,000 ordered, 2,000 invoiced, 1,000 closed: 2,000 more are on their way (or delivered, not drawn up yet).
        drawUp(shipment(UUID.randomUUID(), 40, 0, 5000, DELIVERED_AT));
        when(shipments.shippingFee(any(), any())).thenReturn(Optional.of(shippingFee(4000)));

        assertThat(drafts.drawUpShippingFeeRest(ORDER, DELIVERED_AT)).isEmpty();
        assertThat(saved).as("nothing drawn up").hasSize(1);

        EInvoice.Snapshot last = drawUp(shipment(UUID.randomUUID(), 40, 0, 4000, DELIVERED_AT));
        assertThat(last.lines().getLast().amount()).as("the last delivery makes the fee whole")
                .isEqualByComparingTo("60000");
    }
}
