package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.VietQr;
import com.stockflow.contracts.DesignQuoteRequestClosed;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.InstallmentDue;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.contracts.SampleRoundChanged;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.invoicing.api.IssuedEInvoices;
import com.stockflow.order.api.ContractCards;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.CustomerInstallments;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The cards drawn from other modules' documents (kltn-docs 16 §3 Output): what each card shows, when it is posted, and
 * how it follows its document afterwards - "the card shows the result wherever the customer acted" (16 §4.5 step 12,
 * BR-03). Every fact on a card is read from the module that owns the document, through its {@code api}, when the card is
 * drawn; the events only say that something moved.
 *
 * <p><b>Redelivery.</b> Each method is called from an event listener that Spring Modulith redelivers until it completes,
 * so each is idempotent: a card for a document step already carded is refreshed, never posted twice (a quote's
 * revision, a sample round, an installment, an invoice, a shipment event each name their card).</p>
 *
 * <p><b>BR-06, BR-10, BR-11.</b> The quote request, sample approval and transfer cards are posted here by the system,
 * never typed: there is no endpoint that posts them from a person's text. The transfer details come from configuration
 * (the company's account) and the installment (amount, content), with a VietQR code drawn from them.</p>
 */
@Service
@Transactional
public class DocumentCards {

    private static final Logger log = LoggerFactory.getLogger(DocumentCards.class);
    private static final String VND = "VND";

    private final ChatCards cards;
    private final ChatMessageRepository messages;
    private final ContextResolver contexts;
    private final QuoteRequests quoteRequests;
    private final QuoteCards quotes;
    private final ContractCards contracts;
    private final SampleRoundCards samples;
    private final CustomerInstallments installments;
    private final PaymentService payments;
    private final IssuedEInvoices einvoices;
    private final ObjectMapper json;

    public DocumentCards(ChatCards cards, ChatMessageRepository messages, ContextResolver contexts,
                         QuoteRequests quoteRequests, QuoteCards quotes, ContractCards contracts,
                         SampleRoundCards samples, CustomerInstallments installments, PaymentService payments,
                         IssuedEInvoices einvoices, ObjectMapper json) {
        this.cards = cards;
        this.messages = messages;
        this.contexts = contexts;
        this.quoteRequests = quoteRequests;
        this.quotes = quotes;
        this.contracts = contracts;
        this.samples = samples;
        this.installments = installments;
        this.payments = payments;
        this.einvoices = einvoices;
        this.json = json;
    }

    // ---- quote requests (16 §4.2 step 5, BR-11) ---------------------------------------------------------------------

    /** A customer asked for a quote on a design (or asked again): its card, posted once and refreshed after. */
    public void designQuoteRequested(UUID requestId) {
        quoteRequests.designRequest(requestId).ifPresent(request -> {
            ContextRef design = new ContextRef(ContextKind.DESIGN, request.designId());
            ObjectNode payload = designRequestPayload(request, null);
            Optional<ChatMessage> existing = card(design, CardKind.QUOTE_REQUEST, "requestId", requestId);
            if (existing.isPresent()) {
                cards.update(existing.get().id(), null, keepQuote(existing.get(), payload), Actor.system(), null,
                        null);
            } else if (owned(design, request.customerId())) {
                cards.post(new ChatCards.NewCard(request.customerId(), design, List.of(), CardKind.QUOTE_REQUEST,
                        payload, "Yêu cầu báo giá: " + request.designName(), null, true));
            }
        });
    }

    /** A customer asked one quote over several approved designs of a project: one card of several lines. */
    public void projectQuoteRequested(UUID requestId) {
        quoteRequests.projectRequest(requestId).ifPresent(request -> {
            ContextRef project = new ContextRef(ContextKind.DESIGN_PROJECT, request.projectId());
            Optional<ChatMessage> existing = card(project, CardKind.QUOTE_REQUEST, "requestId", requestId);
            if (existing.isPresent()) {
                // The customer changed the open request (other designs, cartons, day): the same card shows it.
                refreshProjectCard(existing.get(), request);
                return;
            }
            if (!owned(project, request.customerId())) {
                return;
            }
            cards.post(new ChatCards.NewCard(request.customerId(), project, List.of(), CardKind.QUOTE_REQUEST,
                    projectRequestPayload(request), "Yêu cầu báo giá cả dự án: " + request.projectName(), null, true));
        });
    }

    /** Sales closed a project's quote request: its card closes, as a single design's does. */
    public void projectQuoteRequestClosed(UUID requestId) {
        quoteRequests.projectRequest(requestId).ifPresent(request -> card(
                new ContextRef(ContextKind.DESIGN_PROJECT, request.projectId()), CardKind.QUOTE_REQUEST, "requestId",
                requestId).ifPresent(card -> refreshProjectCard(card, request)));
    }

    private void refreshProjectCard(ChatMessage card, QuoteRequests.ProjectRequest request) {
        if (card.card().state() == CardState.SUPERSEDED) {
            return;
        }
        JsonNode payload = keepQuote(card, projectRequestPayload(request));
        CardState state = card.card().state() == CardState.OPEN && "CLOSED".equals(request.status())
                ? CardState.CLOSED : null;
        cards.update(card.id(), state, payload, Actor.system(), null, null);
    }

    private ObjectNode projectRequestPayload(QuoteRequests.ProjectRequest request) {
        ObjectNode payload = json.createObjectNode();
        payload.put("scope", "PROJECT");
        payload.put("requestId", request.id().toString());
        payload.put("projectId", request.projectId().toString());
        payload.put("projectName", request.projectName());
        payload.put("status", request.status());
        payload.putPOJO("desiredDeliveryDate", request.desiredDeliveryDate());
        payload.putPOJO("requestedAt", request.requestedAt());
        payload.set("lines", json.valueToTree(request.lines()));
        return payload;
    }

    /**
     * A design moved (decided, submitted again) or its request closed: its quote request cards show it as it is now -
     * approved with its SKU, back to draft with the reason, or closed (B40: the card follows the design).
     */
    public void designChanged(UUID designId) {
        ContextRef design = new ContextRef(ContextKind.DESIGN, designId);
        for (ChatMessage card : messages.cardsAbout(design, CardKind.QUOTE_REQUEST)) {
            if (card.card().state() == CardState.SUPERSEDED) {
                continue;
            }
            UUID requestId = uuid(cards.readPayload(card.card().payloadJson()), "requestId");
            quoteRequests.designRequest(requestId).ifPresent(request -> {
                JsonNode payload = keepQuote(card, designRequestPayload(request, null));
                boolean closed = "CLOSED".equals(request.status()) || "ARCHIVED".equals(request.designStatus());
                CardState state = card.card().state() == CardState.OPEN && closed ? CardState.CLOSED : null;
                cards.update(card.id(), state, payload, Actor.system(), null, null);
            });
        }
    }

    public void designQuoteRequestClosed(DesignQuoteRequestClosed event) {
        designChanged(event.designId());
    }

    // ---- quotes (16 §4.3 step 7) ------------------------------------------------------------------------------------

    /**
     * A quote moved. Sent: a new card for this revision, the earlier ones superseded. Otherwise every card of the quote
     * shows where it stands - accepted, sent back, converted, expired, cancelled - wherever the customer acted.
     */
    public void quoteChanged(QuoteStatusChanged event) {
        Optional<QuoteCards.Card> found = quotes.quote(event.quoteId());
        if (found.isEmpty()) {
            return;
        }
        QuoteCards.Card quote = found.get();
        ContextRef ref = new ContextRef(ContextKind.QUOTE, quote.id());
        JsonNode payload = quotePayload(quote);
        if ("SENT".equals(event.toStatus())) {
            Optional<ChatMessage> sameRevision = messages.cardsAbout(ref, CardKind.QUOTE).stream()
                    .filter(c -> cards.readPayload(c.card().payloadJson()).path("revision").asInt()
                            == event.revision()).findFirst();
            if (sameRevision.isPresent()) {
                cards.update(sameRevision.get().id(), null, payload, Actor.system(), null, null);
                return;
            }
            if (!owned(ref, quote.customerId())) {
                return;
            }
            UUID posted = cards.post(new ChatCards.NewCard(quote.customerId(), ref, List.of(), CardKind.QUOTE,
                    payload, "Báo giá " + quote.number(), event.changedBy(), false)).messageId();
            cards.supersede(ref, CardKind.QUOTE, posted);
            return;
        }
        CardState state = switch (event.toStatus()) {
            case "ACCEPTED", "CHANGES_REQUESTED", "CONVERTED" -> CardState.ACTED;
            case "EXPIRED" -> CardState.EXPIRED;
            case "CANCELLED" -> CardState.CANCELLED;
            default -> null;
        };
        Actor actor = cards.actorOf(event.changedBy());
        for (ChatMessage card : messages.cardsAbout(ref, CardKind.QUOTE)) {
            if (card.card().state() == CardState.SUPERSEDED) {
                continue;
            }
            boolean latest = cards.readPayload(card.card().payloadJson()).path("revision").asInt() == quote.revision();
            cards.update(card.id(), latest ? state : null, latest ? payload : null, actor, null,
                    "{\"quoteStatus\":\"" + event.toStatus() + "\"}");
        }
    }

    // ---- contracts (16 §4.3 step 8) ---------------------------------------------------------------------------------

    /** Sent: the contract's card with its document and installments; signed or cancelled: the card shows it. */
    public void contractChanged(SalesContractStatusChanged event) {
        Optional<ContractCards.Card> found = contracts.contract(event.orderId(), event.contractId());
        if (found.isEmpty()) {
            return;
        }
        ContractCards.Card contract = found.get();
        ContextRef ref = new ContextRef(ContextKind.CONTRACT, contract.id());
        JsonNode payload = contractPayload(contract);
        List<ChatMessage> existing = messages.cardsAbout(ref, CardKind.CONTRACT);
        if ("SENT".equals(event.status())) {
            if (existing.stream().anyMatch(c -> c.card().state() == CardState.OPEN)) {
                existing.forEach(c -> cards.update(c.id(), null, payload, Actor.system(), null, null));
                return;
            }
            if (owned(ref, event.customerId())) {
                cards.post(new ChatCards.NewCard(event.customerId(), ref,
                        List.of(new ContextRef(ContextKind.ORDER, contract.orderId())), CardKind.CONTRACT, payload,
                        "Hợp đồng " + contract.number(), event.changedBy(), false));
            }
            return;
        }
        CardState state = switch (event.status()) {
            case "SIGNED" -> CardState.ACTED;
            case "CANCELLED" -> CardState.CANCELLED;
            case "COMPLETED" -> CardState.CLOSED;
            default -> null;
        };
        existing.stream().filter(c -> c.card().state() != CardState.SUPERSEDED).forEach(c -> cards.update(c.id(),
                state, payload, Actor.system(), null, null));
    }

    // ---- transfer details (16 §4.4, BR-06) --------------------------------------------------------------------------

    /** An installment fell due: its transfer details, by the system, once per installment. */
    public void installmentDue(InstallmentDue event) {
        Optional<CustomerInstallments.Transfer> found = installments.transfer(event.installmentId());
        if (found.isEmpty() || found.get().outstanding().signum() <= 0) {
            return;
        }
        CustomerInstallments.Transfer transfer = found.get();
        ContextRef ref = new ContextRef(ContextKind.CONTRACT, transfer.contractId());
        if (card(ref, CardKind.PAYMENT_INFO, "installmentId", transfer.installmentId()).isPresent()
                || !owned(ref, transfer.customerId())) {
            return;
        }
        cards.post(new ChatCards.NewCard(transfer.customerId(), ref,
                List.of(new ContextRef(ContextKind.ORDER, transfer.orderId())), CardKind.PAYMENT_INFO,
                transferPayload(transfer, false), "Thông tin chuyển khoản " + transfer.transferReference(), null,
                false));
    }

    /**
     * The customer paid (or a receipt was voided): their open transfer cards show what is left - paid in full, the
     * card is done; cancelled with its order, the card is too.
     */
    public void paymentsChanged(UUID customerId) {
        for (ChatMessage card : messages.openCardsOf(customerId,
                EnumSet.of(CardKind.PAYMENT_INFO, CardKind.PAYMENT_REMINDER))) {
            JsonNode old = cards.readPayload(card.card().payloadJson());
            installments.transfer(uuid(old, "installmentId")).ifPresent(transfer -> {
                CardState state = switch (transfer.status()) {
                    case "PAID" -> CardState.ACTED;
                    case "CANCELLED" -> CardState.CANCELLED;
                    default -> null;
                };
                cards.update(card.id(), state, transferPayload(transfer, old.path("reminder").asBoolean()),
                        Actor.system(), null, null);
            });
        }
    }

    /**
     * The transfer card's data: the company's account from configuration, never typed (BR-06), what the installment
     * still owes, the content accounting matches it by, and the VietQR payload - when the bank's BIN is configured.
     */
    public ObjectNode transferPayload(CustomerInstallments.Transfer transfer, boolean reminder) {
        ObjectNode payload = json.createObjectNode();
        payload.put("installmentId", transfer.installmentId().toString());
        payload.put("contractId", transfer.contractId().toString());
        payload.put("contractNumber", transfer.contractNumber());
        payload.put("orderId", transfer.orderId().toString());
        payload.put("orderNumber", transfer.orderNumber());
        payload.put("seq", transfer.seq());
        payload.put("name", transfer.name());
        payload.put("amount", transfer.outstanding());
        payload.put("installmentAmount", transfer.amount());
        payload.put("currency", transfer.currency());
        payload.putPOJO("dueDate", transfer.dueDate());
        payload.put("overdue", transfer.overdue());
        payload.put("status", transfer.status());
        payload.put("transferContent", transfer.transferReference());
        payload.put("reminder", reminder);
        Optional<CompanyBankAccount> account = payments.companyBankAccount();
        if (account.isPresent()) {
            CompanyBankAccount bank = account.get();
            ObjectNode node = payload.putObject("bank");
            node.put("bankName", bank.bankName());
            node.put("bankBin", bank.bankBin());
            node.put("accountNumber", bank.accountNumber());
            node.put("accountName", bank.accountName());
            boolean payable = transfer.outstanding().signum() > 0 && VND.equals(transfer.currency());
            payload.put("vietQr", bank.bankBin() == null || !payable ? null : VietQr.payload(bank.bankBin(),
                    bank.accountNumber(), transfer.outstanding(), transfer.transferReference()));
        } else {
            payload.putNull("bank");
            payload.putNull("vietQr");
        }
        return payload;
    }

    // ---- print samples (16 §4.5, BR-10) -----------------------------------------------------------------------------

    /** A round waits for the customer: its approval card, the earlier ones superseded. Answered: the card shows it. */
    public void sampleRoundChanged(SampleRoundChanged event) {
        Optional<SampleRoundCards.Card> found = samples.round(event.roundId());
        if (found.isEmpty()) {
            return;
        }
        SampleRoundCards.Card round = found.get();
        ContextRef ref = new ContextRef(ContextKind.PRODUCTION_ORDER, round.productionOrderId());
        JsonNode payload = samplePayload(round);
        Optional<ChatMessage> existing = card(ref, CardKind.SAMPLE_APPROVAL, "roundId", round.roundId());
        if (existing.isPresent()) {
            CardState state = "WAITING".equals(round.status()) ? null : CardState.ACTED;
            cards.update(existing.get().id(), existing.get().card().state() == CardState.OPEN ? state : null, payload,
                    cards.actorOf(null), null, "{\"round\":\"" + round.status() + "\"}");
            return;
        }
        if (!"WAITING".equals(round.status()) || !owned(ref, round.customerId())) {
            return;
        }
        UUID posted = cards.post(new ChatCards.NewCard(round.customerId(), ref,
                List.of(new ContextRef(ContextKind.ORDER, round.salesOrderId())), CardKind.SAMPLE_APPROVAL, payload,
                "Duyệt mẫu in vòng " + round.roundNo() + " - " + round.productionOrderCode(), null, false)).messageId();
        cards.supersede(ref, CardKind.SAMPLE_APPROVAL, posted);
    }

    /** A production order stopped: its open approval cards are closed - nothing is left to approve. */
    public void productionOrderStopped(ProductionOrderStopped event) {
        ContextRef ref = new ContextRef(ContextKind.PRODUCTION_ORDER, event.productionOrderId());
        CardState state = "CANCELLED".equals(event.outcome()) ? CardState.CANCELLED : CardState.CLOSED;
        messages.cardsAbout(ref, CardKind.SAMPLE_APPROVAL).stream().filter(c -> c.card().state() == CardState.OPEN)
                .forEach(c -> cards.update(c.id(), state, null, Actor.system(), null, null));
    }

    // ---- invoices and order status (16 §4.6 step 15) ----------------------------------------------------------------

    /** An e-invoice was issued: its card, to view and download - the chat issues and changes no invoice. */
    public void einvoiceIssued(EInvoiceIssued event) {
        Optional<IssuedEInvoices.Card> found = einvoices.card(event.einvoiceId());
        if (found.isEmpty()) {
            return;
        }
        IssuedEInvoices.Card invoice = found.get();
        ContextRef ref = new ContextRef(ContextKind.EINVOICE, invoice.id());
        if (!messages.cardsAbout(ref, CardKind.INVOICE).isEmpty() || !owned(ref, invoice.customerId())) {
            return;
        }
        ObjectNode payload = json.valueToTree(invoice);
        payload.remove("customerId");
        ObjectNode files = payload.putObject("files");
        String base = "/api/v1/einvoices/me/" + invoice.id() + "/files/";
        files.put("XML", invoice.hasXml() ? base + "XML" : null);
        files.put("PDF", invoice.hasPdf() ? base + "PDF" : null);
        cards.post(new ChatCards.NewCard(invoice.customerId(), ref,
                List.of(new ContextRef(ContextKind.ORDER, invoice.orderId())), CardKind.INVOICE, payload,
                "Hoá đơn " + (invoice.symbol() == null ? "" : invoice.symbol() + " ") + Optional.ofNullable(
                        invoice.invoiceNumber()).map(String::valueOf).orElse(invoice.code()), null, false));
    }

    /** A shipment was handed over, delivered or not delivered: a short card, the order's earlier one superseded. */
    public void shipmentChanged(ShipmentStatusChanged event) {
        if (event.customerId() == null || event.orderId() == null
                || !List.of("HANDED_OVER", "DELIVERED", "DELIVERY_FAILED").contains(event.status())) {
            return;
        }
        ContextRef ref = new ContextRef(ContextKind.ORDER, event.orderId());
        String eventKey = event.eventId() != null ? event.eventId().toString()
                : event.shipmentId() + "@" + event.status();
        boolean told = messages.cardsAbout(ref, CardKind.ORDER_STATUS).stream()
                .anyMatch(c -> eventKey.equals(cards.readPayload(c.card().payloadJson()).path("eventKey").asText()));
        if (told || !owned(ref, event.customerId())) {
            return;
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("eventKey", eventKey);
        payload.put("orderId", event.orderId().toString());
        payload.put("orderNumber", event.orderNumber());
        payload.put("shipmentId", event.shipmentId() == null ? null : event.shipmentId().toString());
        payload.put("shipmentCode", event.shipmentCode());
        payload.put("status", event.status());
        payload.put("failureReasonCode", event.failureReasonCode());
        payload.put("carrierName", event.carrierName());
        payload.put("packageCount", event.packageCount());
        payload.putPOJO("occurredAt", event.occurredAt());
        UUID posted = cards.post(new ChatCards.NewCard(event.customerId(), ref, List.of(), CardKind.ORDER_STATUS,
                payload, "Đơn " + event.orderNumber() + ": " + event.status(), null, false)).messageId();
        cards.supersede(ref, CardKind.ORDER_STATUS, posted);
    }

    // ---- payloads ---------------------------------------------------------------------------------------------------

    /** A design request's card data; {@code quote} the quote drawn up from it, once there is one. */
    public ObjectNode designRequestPayload(QuoteRequests.DesignRequest request, QuoteCards.Card quote) {
        ObjectNode payload = json.createObjectNode();
        payload.put("scope", "DESIGN");
        payload.put("requestId", request.id().toString());
        payload.put("designId", request.designId().toString());
        payload.put("designName", request.designName());
        payload.put("designStatus", request.designStatus());
        payload.put("sku", request.sku());
        payload.put("customerSku", request.customerSku());
        payload.put("returnReason", request.returnReason());
        payload.put("cartons", request.cartons());
        payload.putPOJO("desiredDeliveryDate", request.desiredDeliveryDate());
        payload.put("requestStatus", request.status());
        payload.putPOJO("requestedAt", request.requestedAt());
        if (quote != null) {
            payload.put("quoteId", quote.id().toString());
            payload.put("quoteNumber", quote.number());
        }
        return payload;
    }

    public ObjectNode quotePayload(QuoteCards.Card quote) {
        ObjectNode payload = json.valueToTree(quote);
        payload.remove("customerId");
        payload.remove("sentBy");
        payload.put("quoteId", quote.id().toString());
        payload.put("currency", VND);
        return payload;
    }

    private ObjectNode contractPayload(ContractCards.Card contract) {
        ObjectNode payload = json.valueToTree(contract);
        payload.put("contractId", contract.id().toString());
        payload.put("documentPath", "/api/v1/orders/me/" + contract.orderId() + "/contract/document");
        return payload;
    }

    private ObjectNode samplePayload(SampleRoundCards.Card round) {
        ObjectNode payload = json.valueToTree(round);
        payload.remove("customerId");
        payload.put("mediaPath", "/api/v1/customer/production-samples/" + round.roundId() + "/media/{mediaId}/view-url");
        return payload;
    }

    /** A refreshed request payload keeps the quote drawn up from it, which the design module does not know. */
    private JsonNode keepQuote(ChatMessage card, ObjectNode fresh) {
        JsonNode old = cards.readPayload(card.card().payloadJson());
        if (old.hasNonNull("quoteId")) {
            fresh.set("quoteId", old.get("quoteId"));
            fresh.set("quoteNumber", old.get("quoteNumber"));
        }
        return fresh;
    }

    /** The card of {@code kind} about {@code ref} whose payload names {@code value} in {@code field}. */
    private Optional<ChatMessage> card(ContextRef ref, CardKind kind, String field, UUID value) {
        return messages.cardsAbout(ref, kind).stream()
                .filter(c -> value.toString().equals(cards.readPayload(c.card().payloadJson()).path(field).asText()))
                .findFirst();
    }

    /**
     * Whether the document is the customer's (BR-02), checked before posting: an event about a document that is not -
     * which would be a bug upstream - is logged and dropped rather than retried for ever.
     */
    private boolean owned(ContextRef ref, UUID customerId) {
        boolean owned = customerId != null && contexts.find(ref)
                .filter(found -> found.customerId() == null || found.customerId().equals(customerId)).isPresent();
        if (!owned) {
            log.warn("No card for {} {}: not a document of customer {}", ref.kind(), ref.id(), customerId);
        }
        return owned;
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
