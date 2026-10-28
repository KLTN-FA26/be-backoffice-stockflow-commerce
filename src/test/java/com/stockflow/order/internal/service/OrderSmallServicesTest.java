package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.repository.OrderLineReferences;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.product.api.SkuReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The small collaborators of the order module: stock allocation at release, the fulfillment queue, quote sweep, SKU probe. */
class OrderSmallServicesTest {

    @Nested
    @DisplayName("stock allocation at release (F-FUL step 02)")
    class StockAllocation {

        private final InventoryService inventory = mock(InventoryService.class);
        private final OrderStockAllocation allocation = new OrderStockAllocation(inventory);

        private StockReservation hold() {
            StockReservation hold = mock(StockReservation.class);
            when(hold.reservationId()).thenReturn(UUID.randomUUID());
            return hold;
        }

        @Test
        @DisplayName("each checkout reservation is swapped on its line for the ids of the allocations that replaced it")
        void swapsReservationsForAllocations() {
            Order order = OrderFixtures.releasedPrepaid();
            UUID cupReservation = order.lines().get(0).reservationIds().get(0);
            UUID lidReservation = order.lines().get(1).reservationIds().get(0);
            StockReservation first = hold();
            StockReservation second = hold();
            StockReservation lidHold = hold();
            when(inventory.allocate(any())).thenReturn(new AllocateStockResult(order.id().value(), "HCM", List.of(
                    new AllocateStockResult.Allocation(cupReservation, "CUP-12OZ", List.of(first, second)),
                    new AllocateStockResult.Allocation(lidReservation, "LID-90", List.of(lidHold)))));

            int allocated = allocation.allocate(order, "HCM");

            assertThat(allocated).isEqualTo(2);
            assertThat(order.lines().get(0).reservationIds()).containsExactly(first.reservationId(),
                    second.reservationId());
            assertThat(order.lines().get(1).reservationIds()).containsExactly(lidHold.reservationId());
            ArgumentCaptor<AllocateStockCommand> command = ArgumentCaptor.forClass(AllocateStockCommand.class);
            verify(inventory).allocate(command.capture());
            assertThat(command.getValue().orderId()).isEqualTo(order.id().value());
            assertThat(command.getValue().warehouseCode()).isEqualTo("HCM");
        }

        @Test
        @DisplayName("an order allocated already (or placed before SCRUM-152) allocates 0 and its lines stay as they are")
        void nothingToAllocate() {
            Order order = OrderFixtures.releasedPrepaid();
            List<UUID> before = order.reservationIds();
            when(inventory.allocate(any())).thenReturn(new AllocateStockResult(order.id().value(), "HCM", List.of()));

            assertThat(allocation.allocate(order, "HCM")).isZero();
            assertThat(order.reservationIds()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("orders waiting for a fulfillment task (kltn-docs 07 step 1)")
    class Candidates {

        private final OrderSearchRepository search = mock(OrderSearchRepository.class);
        private final CustomerService customers = mock(CustomerService.class);
        private final FulfillmentCandidateService service = new FulfillmentCandidateService(search, customers);

        private FulfillmentCandidate candidate(UUID customer) {
            return new FulfillmentCandidate(UUID.randomUUID(), "SO-1", customer, null, UUID.randomUUID(), NOW, NOW,
                    List.of());
        }

        @Test
        @DisplayName("the customers of the whole page are named in one query, and the order of the page is kept")
        void namesTheCustomersInOneLookup() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            FulfillmentCandidate first = candidate(a);
            FulfillmentCandidate second = candidate(b);
            FulfillmentCandidate third = candidate(a);
            when(search.awaitingTask(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second,
                    third)));
            when(customers.fullNames(any())).thenReturn(Map.of(a, "Acme", b, "Bravo"));

            PageResponse<FulfillmentCandidate> page = service.awaitingTask(UUID.randomUUID(), 0, 20);

            assertThat(page.items()).extracting(FulfillmentCandidate::customerName)
                    .containsExactly("Acme", "Bravo", "Acme");
            assertThat(page.items()).extracting(FulfillmentCandidate::orderId)
                    .containsExactly(first.orderId(), second.orderId(), third.orderId());
            ArgumentCaptor<java.util.Collection<UUID>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(customers, org.mockito.Mockito.times(1)).fullNames(asked.capture());
            assertThat(asked.getValue()).containsExactlyInAnyOrder(a, b);
        }

        @Test
        @DisplayName("the page is sorted first released first, the order number breaking ties")
        void firstReleasedFirst() {
            when(search.awaitingTask(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
            when(customers.fullNames(any())).thenReturn(Map.of());

            service.awaitingTask(UUID.randomUUID(), 2, 10);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(search).awaitingTask(any(), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
            assertThat(pageable.getValue().getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()))
                    .containsExactly("releasedAt:ASC", "orderNumber:ASC");
        }

        @Test
        @DisplayName("a customer without a name stays unnamed, and a guest order (no customer) is not looked up")
        void guestsAndUnknownCustomers() {
            UUID known = UUID.randomUUID();
            when(search.awaitingTask(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                    candidate(null), candidate(known))));
            // The real lookup answers with a HashMap once at least one customer was asked for.
            when(customers.fullNames(any())).thenReturn(new java.util.HashMap<>());

            PageResponse<FulfillmentCandidate> page = service.awaitingTask(UUID.randomUUID(), 0, 20);

            assertThat(page.items()).extracting(FulfillmentCandidate::customerName).containsOnlyNulls();
            ArgumentCaptor<java.util.Collection<UUID>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(customers).fullNames(asked.capture());
            assertThat(asked.getValue()).containsExactly(known);
        }
    }

    @Test
    @DisplayName("the quote sweep expires what is due in batches of 100 and is quiet when nothing is due")
    void quoteSweep() {
        Quotes quotes = mock(Quotes.class);
        QuoteExpirySweeper sweeper = new QuoteExpirySweeper(quotes);
        when(quotes.expireDue(QuoteExpirySweeper.BATCH_SIZE)).thenReturn(3, 0);

        sweeper.expireQuotes();
        sweeper.expireQuotes();

        assertThat(QuoteExpirySweeper.BATCH_SIZE).isEqualTo(100);
        verify(quotes, org.mockito.Mockito.times(2)).expireDue(100);
    }

    @Test
    @DisplayName("open references of a SKU are the unshipped order lines, reported as SALES_ORDER_LINE (BR-PRD-005)")
    void skuReferences() {
        OrderLineReferences lines = mock(OrderLineReferences.class);
        UUID orderId = UUID.randomUUID();
        when(lines.unshippedLines(List.of("CUP-12OZ"), 5)).thenReturn(List.of(
                new OrderLineReferences.Row(orderId, "SO-20261027-000001", "CUP-12OZ")));

        List<SkuReference> references = new OrderSkuReferenceProbe(lines).openReferences(List.of("CUP-12OZ"), 5);

        assertThat(references).singleElement().satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(SkuReference.Kind.SALES_ORDER_LINE);
            assertThat(reference.sku()).isEqualTo("CUP-12OZ");
            assertThat(reference.documentId()).isEqualTo(orderId);
            assertThat(reference.documentNumber()).isEqualTo("SO-20261027-000001");
        });
    }
}
