package com.stockflow.payment.internal.controller;

import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.internal.domain.RefundRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A page of refund records names who recorded and voided each one and whose it is, read once for the page - never one
 * identity or customer read per row - and the customer's own copy names neither staff nor customer.
 */
class RefundRecordNamesTest {

    private final IdentityService identities = mock(IdentityService.class);
    private final CustomerService customers = mock(CustomerService.class);

    private static RefundRecord record(UUID customer, UUID recordedBy, UUID voidedBy) {
        return new RefundRecord(UUID.randomUUID(), UUID.randomUUID(), customer, "SO-20261108-000007",
                new BigDecimal("150000"), "VND", LocalDate.of(2026, 11, 20), "FT26313987654", null,
                voidedBy == null ? RefundRecord.Status.RECORDED : RefundRecord.Status.VOIDED, recordedBy,
                Instant.parse("2026-11-20T03:00:00Z"), voidedBy,
                voidedBy == null ? null : Instant.parse("2026-11-21T03:00:00Z"),
                voidedBy == null ? null : "Sent to the wrong account");
    }

    @Test
    void aPageNamesItsPeopleAndCustomersInOneReadEach() {
        UUID accountant = UUID.randomUUID();
        UUID chief = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        RefundRecord voided = record(customer, accountant, chief);
        RefundRecord live = record(customer, accountant, null);
        when(identities.displayNames(anyCollection())).thenReturn(Map.of(accountant, "Kế Toán", chief, "Kế Toán Trưởng"));
        when(customers.labels(anyCollection())).thenReturn(Map.of(customer,
                new CustomerLabel(customer, "Quán Cà Phê A", "0312345678")));

        List<RefundRecord> page = List.of(voided, live);
        var names = PaymentWebMapper.namesOf(identities, page);
        var labels = PaymentWebMapper.labelsOf(customers, page);
        var shown = page.stream().map(r -> PaymentWebMapper.toResponse(r, names, labels)).toList();

        assertThat(shown.get(0).recordedByName()).isEqualTo("Kế Toán");
        assertThat(shown.get(0).voidedByName()).isEqualTo("Kế Toán Trưởng");
        assertThat(shown.get(1).voidedByName()).as("not voided").isNull();
        assertThat(shown).allSatisfy(r -> {
            assertThat(r.customerName()).isEqualTo("Quán Cà Phê A");
            assertThat(r.customerCode()).isEqualTo("0312345678");
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(identities, times(1)).displayNames(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder(accountant, chief);
        verify(customers, times(1)).labels(anyCollection());
    }

    @Test
    void anEmptyPageReadsNothing() {
        assertThat(PaymentWebMapper.namesOf(identities, List.of())).isEmpty();
        assertThat(PaymentWebMapper.labelsOf(customers, List.of())).isEmpty();
        verify(identities, never()).displayNames(anyCollection());
        verify(customers, never()).labels(anyCollection());
    }
}
