package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.chat.api.PostedCard;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.chat.internal.domain.Visibility;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.invoicing.api.IssuedEInvoices;
import com.stockflow.order.api.ContractCards;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.CustomerInstallments;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cards with no action of their own - the contract (16 §4.3 step 8) and the invoice (§4.6 step 15) - follow their
 * documents; and routing in the order of 16 §4.1 step 2. The chat's own writing ({@link ChatCards}) and the documents'
 * modules are stood in for.
 */
class DocumentCardsTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ChatCards cards = mock(ChatCards.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ContextResolver contexts = mock(ContextResolver.class);
    private final ContractCards contracts = mock(ContractCards.class);
    private final IssuedEInvoices einvoices = mock(IssuedEInvoices.class);
    private final QuoteRequests quoteRequests = mock(QuoteRequests.class);
    private final CustomerInstallments installments = mock(CustomerInstallments.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final QuoteCards quotes = mock(QuoteCards.class);
    private DocumentCards documents;

    private final UUID customer = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documents = new DocumentCards(cards, messages, contexts, quoteRequests, quotes,
                contracts, mock(SampleRoundCards.class), installments, payments, einvoices, json);
        when(contexts.find(any())).thenAnswer(call -> Optional.of(new ContextResolver.Resolved(call.getArgument(0),
                customer, "X", "ANY", null)));
        when(cards.post(any())).thenReturn(new PostedCard(UUID.randomUUID(), UUID.randomUUID(), 1));
        when(cards.readPayload(any())).thenAnswer(call -> json.readTree((String) call.getArgument(0)));
    }

    @Test
    @DisplayName("CONTRACT: sent - a card with its document and installments; signed - the card shows it, ACTED")
    void contractCard() {
        UUID contract = UUID.randomUUID();
        when(contracts.contract(order, contract)).thenReturn(Optional.of(new ContractCards.Card(contract,
                "HD-1", order, "SO-1", "SENT", new BigDecimal("1000"), "VND", 2,
                List.of(new ContractCards.Installment(1, "Dot 1", "ON_SIGNING", new BigDecimal("300"), null, 7, null)),
                Instant.now(), null, null, 1)));
        documents.contractChanged(new SalesContractStatusChanged(contract, "HD-1", order, "SO-1", customer, "SENT",
                UUID.randomUUID(), Instant.now()));
        ArgumentCaptor<ChatCards.NewCard> posted = ArgumentCaptor.forClass(ChatCards.NewCard.class);
        verify(cards).post(posted.capture());
        assertThat(posted.getValue().kind()).isEqualTo(CardKind.CONTRACT);
        assertThat(posted.getValue().about()).isEqualTo(new ContextRef(ContextKind.CONTRACT, contract));
        assertThat(posted.getValue().related()).containsExactly(new ContextRef(ContextKind.ORDER, order));
        assertThat(posted.getValue().payload().path("documentPath").asText())
                .isEqualTo("/api/v1/orders/me/" + order + "/contract/document");
        assertThat(posted.getValue().payload().path("paymentPlan").get(0).path("milestone").asText())
                .isEqualTo("ON_SIGNING");

        ChatMessage card = card(CardKind.CONTRACT, "{\"contractId\":\"" + contract + "\"}");
        when(messages.cardsAbout(new ContextRef(ContextKind.CONTRACT, contract), CardKind.CONTRACT))
                .thenReturn(List.of(card));
        documents.contractChanged(new SalesContractStatusChanged(contract, "HD-1", order, "SO-1", customer, "SIGNED",
                UUID.randomUUID(), Instant.now()));
        verify(cards).update(eq(card.id()), eq(CardState.ACTED), any(), eq(Actor.system()), isNull(), isNull());
    }

    @Test
    @DisplayName("INVOICE: issued - a card with number, symbol, totals and the customer's file links; once")
    void invoiceCard() {
        UUID invoice = UUID.randomUUID();
        when(einvoices.card(invoice)).thenReturn(Optional.of(new IssuedEInvoices.Card(invoice, customer, "INV-1",
                "ISSUED", order, "SO-1", "1", "C26TAA", 123, LocalDate.of(2026, 11, 13), "VND",
                new BigDecimal("1000"), new BigDecimal("80"), new BigDecimal("1080"), true, true)));
        EInvoiceIssued issued = new EInvoiceIssued(invoice, "INV-1", order, "SO-1", null, customer, Instant.now());
        documents.einvoiceIssued(issued);
        ArgumentCaptor<ChatCards.NewCard> posted = ArgumentCaptor.forClass(ChatCards.NewCard.class);
        verify(cards).post(posted.capture());
        assertThat(posted.getValue().kind()).isEqualTo(CardKind.INVOICE);
        assertThat(posted.getValue().payload().path("symbol").asText()).isEqualTo("C26TAA");
        assertThat(posted.getValue().payload().path("grandTotal").decimalValue()).isEqualByComparingTo("1080");
        assertThat(posted.getValue().payload().path("files").path("PDF").asText())
                .isEqualTo("/api/v1/einvoices/me/" + invoice + "/files/PDF");
        assertThat(posted.getValue().payload().has("customerId")).isFalse();
        assertThat(posted.getValue().sentBy()).as("the system's card").isNull();

        when(messages.cardsAbout(new ContextRef(ContextKind.EINVOICE, invoice), CardKind.INVOICE))
                .thenReturn(List.of(card(CardKind.INVOICE, "{}")));
        documents.einvoiceIssued(issued);
        verify(cards).post(any());
    }

    @Test
    @DisplayName("QUOTE_REQUEST of a project: a changed open request refreshes its card; closed by Sales, the card closes")
    void projectQuoteRequestCard() {
        UUID request = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        QuoteRequests.ProjectLine line = new QuoteRequests.ProjectLine(UUID.randomUUID(), "Ly 12oz", "APPROVED",
                "SKU-1", 30);
        when(quoteRequests.projectRequest(request)).thenReturn(Optional.of(new QuoteRequests.ProjectRequest(request,
                project, "Du an A", customer, LocalDate.of(2026, 12, 1), Instant.now(), "OPEN", List.of(line))));
        ChatMessage card = card(CardKind.QUOTE_REQUEST, "{\"requestId\":\"" + request + "\"}");
        when(messages.cardsAbout(new ContextRef(ContextKind.DESIGN_PROJECT, project), CardKind.QUOTE_REQUEST))
                .thenReturn(List.of(card));

        documents.projectQuoteRequested(request);
        verify(cards, never()).post(any());
        verify(cards).update(eq(card.id()), isNull(), any(), eq(Actor.system()), isNull(), isNull());

        when(quoteRequests.projectRequest(request)).thenReturn(Optional.of(new QuoteRequests.ProjectRequest(request,
                project, "Du an A", customer, LocalDate.of(2026, 12, 1), Instant.now(), "CLOSED", List.of(line))));
        documents.projectQuoteRequestClosed(request);
        verify(cards).update(eq(card.id()), eq(CardState.CLOSED), any(), eq(Actor.system()), isNull(), isNull());
    }

    @Test
    @DisplayName("BR-02: a document that is not the customer's gets no card")
    void notTheCustomersDocument() {
        UUID invoice = UUID.randomUUID();
        when(einvoices.card(invoice)).thenReturn(Optional.of(new IssuedEInvoices.Card(invoice, customer, "INV-2",
                "ISSUED", order, "SO-1", "1", "C26TAA", 1, LocalDate.now(), "VND", BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, false, false)));
        when(contexts.find(any())).thenAnswer(call -> Optional.of(new ContextResolver.Resolved(call.getArgument(0),
                UUID.randomUUID(), "X", "ANY", null)));
        documents.einvoiceIssued(new EInvoiceIssued(invoice, "INV-2", order, "SO-1", null, customer, Instant.now()));
        verify(cards, never()).post(any());
    }

    @Test
    @DisplayName("16 §4.1 step 2: the document's Sales, else the customer's last agent still answering, else the queue")
    void routingOrder() {
        AgentDirectory agents = mock(AgentDirectory.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        SalesRouter router = new SalesRouter(agents, conversations);
        UUID owner = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        Conversation c = Conversation.open(UUID.randomUUID(), customer, null, null, Actor.customer(UUID.randomUUID()),
                null, Instant.now(), java.time.Duration.ofMinutes(15));
        ContextResolver.Resolved quote = new ContextResolver.Resolved(new ContextRef(ContextKind.QUOTE,
                UUID.randomUUID()), customer, "QT-1", "SENT", owner);
        when(conversations.recentAgentsOf(customer, c.id(), 5)).thenReturn(List.of(gone, last));

        when(agents.agents()).thenReturn(Set.of(owner, last));
        assertThat(router.agentFor(c, List.of(quote))).contains(owner);
        when(agents.agents()).thenReturn(Set.of(last));
        assertThat(router.agentFor(c, List.of(quote))).as("the owner left: the last agent").contains(last);
        assertThat(router.agentFor(c, List.of())).contains(last);
        when(agents.agents()).thenReturn(Set.of());
        assertThat(router.agentFor(c, List.of(quote))).as("nobody: the queue").isEmpty();
    }

    private CustomerInstallments.Transfer transfer(UUID installment, String status, String outstanding) {
        return new CustomerInstallments.Transfer(installment, customer, UUID.randomUUID(), "HD-1", order, "SO-1", 1,
                "Đặt cọc khi ký", new BigDecimal("22680000"), new BigDecimal(outstanding), "VND",
                LocalDate.of(2026, 11, 23), status, false, "HD-1 DOT1");
    }

    private ChatMessage cardIn(CardState state, UUID installment) {
        ChatMessage open = card(CardKind.PAYMENT_INFO, "{\"installmentId\":\"" + installment + "\",\"reminder\":false}");
        return new ChatMessage(open.id(), open.conversationId(), open.seq(), open.kind(), open.visibility(),
                open.senderType(), null, null, null, open.contextId(),
                new ChatMessage.Card(CardKind.PAYMENT_INFO, open.card().payloadJson(), state, null), open.sentAt(), null,
                null, null, null, null);
    }

    @Test
    @DisplayName("A2: a receipt voided after the card said paid opens the card again, owing, with its VietQR")
    void voidReopensAPaidCard() {
        UUID installment = UUID.randomUUID();
        ChatMessage paid = cardIn(CardState.ACTED, installment);
        when(messages.cardsOf(eq(customer), any(), any())).thenReturn(List.of(paid));
        when(installments.transfer(installment)).thenReturn(Optional.of(transfer(installment, "DUE", "22680000")));
        when(payments.companyBankAccount()).thenReturn(Optional.of(
                new CompanyBankAccount("DEMO BANK", "0000000000", "DEMO STOCKFLOW", "970000")));

        documents.paymentsChanged(customer);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(cards).update(eq(paid.id()), eq(CardState.OPEN), payload.capture(), any(), isNull(), isNull());
        assertThat(payload.getValue().path("amount").decimalValue()).isEqualByComparingTo("22680000");
        assertThat(payload.getValue().path("vietQr").isTextual()).isTrue();
        verify(messages).cardsOf(eq(customer), any(), eq(EnumSet.of(CardState.OPEN, CardState.ACTED)));
    }

    @Test
    @DisplayName("A1: an order cancelled - its card is CANCELLED and carries no VietQR, though money was left owing")
    void cancelledOrderCardHasNoCode() {
        UUID installment = UUID.randomUUID();
        ChatMessage open = cardIn(CardState.OPEN, installment);
        when(messages.cardsOf(eq(customer), any(), any())).thenReturn(List.of(open));
        when(installments.transfer(installment)).thenReturn(Optional.of(transfer(installment, "CANCELLED", "12680000")));
        when(payments.companyBankAccount()).thenReturn(Optional.of(
                new CompanyBankAccount("DEMO BANK", "0000000000", "DEMO STOCKFLOW", "970000")));

        documents.paymentsChanged(customer);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(cards).update(eq(open.id()), eq(CardState.CANCELLED), payload.capture(), any(), isNull(), isNull());
        assertThat(payload.getValue().path("vietQr").isNull()).isTrue();
    }

    @Test
    @DisplayName("a card already showing what is left is not touched: no update, so no conversation lock")
    void unchangedCardIsLeftAlone() throws Exception {
        UUID installment = UUID.randomUUID();
        CustomerInstallments.Transfer paid = transfer(installment, "PAID", "0");
        when(installments.transfer(installment)).thenReturn(Optional.of(paid));
        when(payments.companyBankAccount()).thenReturn(Optional.of(
                new CompanyBankAccount("DEMO BANK", "0000000000", "DEMO STOCKFLOW", "970000")));
        String stored = json.writeValueAsString(documents.transferPayload(paid, false));
        ChatMessage card = cardIn(CardState.ACTED, installment);
        ChatMessage same = new ChatMessage(card.id(), card.conversationId(), card.seq(), card.kind(), card.visibility(),
                card.senderType(), null, null, null, card.contextId(),
                new ChatMessage.Card(CardKind.PAYMENT_INFO, stored, CardState.ACTED, null), card.sentAt(), null, null,
                null, null, null);
        when(messages.cardsOf(eq(customer), any(), any())).thenReturn(List.of(same));

        documents.paymentsChanged(customer);

        verify(cards, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("B13: an installment turned overdue - its card says so, still payable with its code")
    void overdueCard() {
        UUID installment = UUID.randomUUID();
        ChatMessage open = cardIn(CardState.OPEN, installment);
        when(messages.cardsOf(eq(customer), any(), any())).thenReturn(List.of(open));
        CustomerInstallments.Transfer due = transfer(installment, "DUE", "22680000");
        when(installments.transfer(installment)).thenReturn(Optional.of(new CustomerInstallments.Transfer(
                due.installmentId(), due.customerId(), due.contractId(), due.contractNumber(), due.orderId(),
                due.orderNumber(), due.seq(), due.name(), due.amount(), due.outstanding(), due.currency(), due.dueDate(),
                due.status(), true, due.transferReference())));
        when(payments.companyBankAccount()).thenReturn(Optional.of(
                new CompanyBankAccount("DEMO BANK", "0000000000", "DEMO STOCKFLOW", "970000")));

        documents.paymentsChanged(customer);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(cards).update(eq(open.id()), eq(CardState.OPEN), payload.capture(), any(), isNull(), isNull());
        assertThat(payload.getValue().path("overdue").asBoolean()).isTrue();
        assertThat(payload.getValue().path("vietQr").isTextual()).isTrue();
    }

    @Test
    @DisplayName("B1: a quote cancelled while revision 2 was a draft - revision 1's card ends too, on its own terms")
    void earlierRevisionCardEnds() throws Exception {
        UUID quote = UUID.randomUUID();
        ContextRef ref = new ContextRef(ContextKind.QUOTE, quote);
        when(quotes.quote(quote)).thenReturn(Optional.of(new QuoteCards.Card(quote, "QT-1", customer, "CANCELLED", 2,
                Instant.now(), null, List.of(), new BigDecimal("999"), null, null, null, null, null, 7)));
        ChatMessage first = card(CardKind.QUOTE,
                "{\"quoteId\":\"" + quote + "\",\"revision\":1,\"status\":\"CHANGES_REQUESTED\",\"proposedTotal\":100}");
        ChatMessage acted = new ChatMessage(first.id(), first.conversationId(), first.seq(), first.kind(),
                first.visibility(), first.senderType(), null, null, null, first.contextId(),
                new ChatMessage.Card(CardKind.QUOTE, first.card().payloadJson(), CardState.ACTED, null), first.sentAt(),
                null, null, null, null, null);
        when(messages.cardsAbout(ref, CardKind.QUOTE)).thenReturn(List.of(acted));

        documents.quoteChanged(new QuoteStatusChanged(quote, "QT-1", customer, 2, "DRAFT", "CANCELLED", null,
                Instant.now()));

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(cards).update(eq(acted.id()), eq(CardState.CANCELLED), payload.capture(), any(), isNull(), any());
        assertThat(payload.getValue().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(payload.getValue().path("proposedTotal").asInt()).as("its own terms, not the unsent draft's")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("a card paid in full stays ACTED and draws no code")
    void paidStaysActed() {
        UUID installment = UUID.randomUUID();
        ChatMessage paid = cardIn(CardState.ACTED, installment);
        when(messages.cardsOf(eq(customer), any(), any())).thenReturn(List.of(paid));
        when(installments.transfer(installment)).thenReturn(Optional.of(transfer(installment, "PAID", "0")));
        when(payments.companyBankAccount()).thenReturn(Optional.of(
                new CompanyBankAccount("DEMO BANK", "0000000000", "DEMO STOCKFLOW", "970000")));

        documents.paymentsChanged(customer);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(cards).update(eq(paid.id()), eq(CardState.ACTED), payload.capture(), any(), isNull(), isNull());
        assertThat(payload.getValue().path("vietQr").isNull()).isTrue();
    }

    private ChatMessage card(CardKind kind, String payload) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), 1, MessageKind.CARD, Visibility.CUSTOMER,
                SenderType.SYSTEM, null, null, null, UUID.randomUUID(),
                new ChatMessage.Card(kind, payload, CardState.OPEN, null), Instant.now(), null, null, null, null,
                null);
    }
}
