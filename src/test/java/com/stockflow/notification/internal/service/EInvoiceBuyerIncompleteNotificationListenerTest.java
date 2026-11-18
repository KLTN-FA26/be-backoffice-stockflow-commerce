package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceBuyerIncomplete;
import com.stockflow.contracts.EInvoiceBuyerIncomplete.Missing;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HĐ-8 (kltn-docs 21 BR-06: "thiếu thì chặn, báo Sale bổ sung"): Sales, who keep the customer's legal profile, are told
 * what an e-invoice waits for - once per invoice and gap.
 */
class EInvoiceBuyerIncompleteNotificationListenerTest {

    private static final UUID INVOICE = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final EInvoiceBuyerIncompleteNotificationListener listener =
            new EInvoiceBuyerIncompleteNotificationListener(staff, inbox);
    private final UUID sales = UUID.randomUUID();

    private static EInvoiceBuyerIncomplete event(Missing... missing) {
        return new EInvoiceBuyerIncomplete(INVOICE, "EINV-20261112-0001", UUID.randomUUID(), "SO-20261101-0007",
                CUSTOMER, List.of(missing));
    }

    @Test
    @DisplayName("a missing invoice e-mail: Sales are told on the customer, in words, with the invoice and order")
    void aMissingEmailIsTold() {
        when(staff.activeHolders("customer-legal-profiles:UPDATE", null)).thenReturn(List.of(
                new StaffContact(sales, "sale@example.com")));

        listener.on(event(Missing.EMAIL));

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(sales)), message.capture());
        InboxMessage sent = message.getValue();
        assertThat(sent.category()).isEqualTo("EINVOICE_BUYER_INCOMPLETE");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.title()).contains("SO-20261101-0007");
        assertThat(sent.body()).contains("EINV-20261112-0001", "email nhận hoá đơn");
        assertThat(sent.link()).isEqualTo("/customers/" + CUSTOMER);
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-buyer-incomplete:" + INVOICE + ":EMAIL");
    }

    @Test
    @DisplayName("the same gap again tells nobody twice; another gap is told")
    void onceAGap() {
        String both = EInvoiceBuyerIncompleteNotificationListener.message(event(Missing.EMAIL, Missing.TAX_CODE))
                .dedupeKey();
        assertThat(EInvoiceBuyerIncompleteNotificationListener.message(event(Missing.TAX_CODE, Missing.EMAIL))
                .dedupeKey()).isEqualTo(both);
        assertThat(EInvoiceBuyerIncompleteNotificationListener.message(event(Missing.EMAIL)).dedupeKey())
                .isNotEqualTo(both);
        assertThat(EInvoiceBuyerIncompleteNotificationListener.message(event(Missing.values())).body())
                .contains("tên công ty", "mã số thuế", "địa chỉ xuất hoá đơn", "email nhận hoá đơn");
    }

    @Test
    @DisplayName("nobody keeps customers' profiles: nothing is posted, and nothing fails")
    void nobodyToTell() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(event(Missing.EMAIL));

        verify(inbox, never()).post(any(), any());
    }
}
