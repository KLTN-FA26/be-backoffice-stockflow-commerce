package com.stockflow.chat.internal.service;

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
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.invoicing.api.IssuedEInvoices;
import com.stockflow.order.api.ContractCards;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.payment.api.CustomerInstallments;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    private DocumentCards documents;

    private final UUID customer = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documents = new DocumentCards(cards, messages, contexts, mock(QuoteRequests.class), mock(QuoteCards.class),
                contracts, mock(SampleRoundCards.class), mock(CustomerInstallments.class), mock(PaymentService.class),
                einvoices, json);
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

    private ChatMessage card(CardKind kind, String payload) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), 1, MessageKind.CARD, Visibility.CUSTOMER,
                SenderType.SYSTEM, null, null, null, UUID.randomUUID(),
                new ChatMessage.Card(kind, payload, CardState.OPEN, null), Instant.now(), null, null, null, null,
                null);
    }
}
