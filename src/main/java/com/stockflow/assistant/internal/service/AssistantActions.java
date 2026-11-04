package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.assistant.internal.domain.AssistantActionRepository;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.GoodsReceiptDrafting;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the assistant suggests its user do, and the one way a suggestion becomes a document (ADR-0011, design §8.2).
 *
 * <p><b>The model writes nothing.</b> A purchase-order draft is suggested when an answer that ran
 * {@code proposeReorder} is stored, in the same transaction; a goods-receipt draft when a delivery note's photo has
 * been read. The document is created only by its owner, through {@link #confirmPurchaseOrder} or
 * {@link #confirmGoodsReceipt}, whose endpoints carry the guard of that document's own screen: who may not create a
 * purchase order on the purchase-order screen may not create one here.</p>
 *
 * <p><b>Once.</b> The suggestion's row is locked; the document and the row's CONFIRMED are written in one transaction.
 * A second confirmation returns the document the first created. A refused one (a quantity under the supplier's MOQ, a
 * warehouse outside the user's scope) rolls back whole and leaves the suggestion open, to correct and confirm
 * again.</p>
 *
 * <p><b>One open suggestion per need.</b> A new purchase-order suggestion supersedes its owner's open one for the same
 * supplier when it orders every SKU that one does: the same question asked again. Without that, two answers leave two
 * drafts to confirm, and {@code proposeReorder} does not subtract draft purchase orders, so both would order the same
 * goods. One with a SKU the new one lacks (cups asked for, then lids, of one supplier) stays open: it is another need.
 * A delivery note's reading supersedes nothing, since a second reading of one order may be a second truck as well as
 * a retake; confirming a receipt when its owner confirmed another for the same order within a day reports
 * {@code possibleDuplicate}, as a purchase order reports the purchase-order screen's.</p>
 *
 * <p><b>What the document's own module checks, it checks.</b> Prices are the supplier's current catalog prices
 * ({@code unitPrice} null), MOQ and packs, the supplier being active, the warehouse in scope, a receipt's tolerance,
 * lots, expiry and receiving area: all procurement's rules, applied by procurement. Here: whose suggestion it is,
 * whether it is still open, and that what is confirmed is what was suggested — the same supplier, the same SKUs (with
 * changed quantities, or some removed), the same purchase order.</p>
 */
@Service
public class AssistantActions {

    private static final Logger log = LoggerFactory.getLogger(AssistantActions.class);

    /** The receipt screen's guard is CREATE to start a receipt and UPDATE to count it; confirming does both. */
    static final String GOODS_RECEIPTS = ToolPermissions.GOODS_RECEIPTS;

    /** The proposal type {@code proposeReorder} writes, which this turns into a purchase-order suggestion. */
    static final String PURCHASE_ORDER_PROPOSAL = "PURCHASE_ORDER_DRAFT";

    private final AssistantActionRepository actions;
    private final ProcurementService procurement;
    private final SupplierItemService supplierItems;
    private final GoodsReceiptDrafting receipts;
    private final ReceiptDraftService receiptDrafts;
    private final ObjectMapper json;
    private final Clock clock;
    private final AssistantTelemetry telemetry;

    AssistantActions(AssistantActionRepository actions, ProcurementService procurement,
                     SupplierItemService supplierItems, GoodsReceiptDrafting receipts,
                     ReceiptDraftService receiptDrafts, ObjectMapper json, Clock clock, AssistantTelemetry telemetry) {
        this.actions = actions;
        this.procurement = procurement;
        this.supplierItems = supplierItems;
        this.receipts = receipts;
        this.receiptDrafts = receiptDrafts;
        this.json = json;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    /**
     * A suggestion as its owner reads it: what was suggested, what they decided, the document it became.
     *
     * @param possibleDuplicate only on the answer to the confirmation that created a purchase order: the purchase-order
     *                          screen's own warning that a similar order exists; null otherwise
     */
    public record ActionView(UUID id, AssistantAction.Kind kind, AssistantAction.Status status, Instant createdAt,
                             Instant expiresAt, Instant decidedAt, UUID resultId, String resultNumber,
                             JsonNode suggested, JsonNode submitted, Boolean possibleDuplicate) {

        ActionView withPossibleDuplicate(boolean duplicate) {
            return new ActionView(id, kind, status, createdAt, expiresAt, decidedAt, resultId, resultNumber, suggested,
                    submitted, duplicate);
        }
    }

    /** A purchase order as its owner confirms it: where it is received, when, and the lines they keep. */
    public record PurchaseOrderChoice(UUID warehouseId, LocalDate expectedAt, String note, List<OrderLine> lines) {
    }

    public record OrderLine(String sku, int quantity) {
    }

    /** A receipt as its owner confirms it: the count, each line on a line of the order. */
    public record GoodsReceiptChoice(String deliveryNote, String note, String locationCode,
                                     List<ReceiptLine> lines) {
    }

    /** @param locationCode the receiving area; null for the choice's own */
    public record ReceiptLine(int poLineNo, int quantity, String lotNumber, LocalDate expiryDate,
                              String locationCode, String note) {
    }

    // ------------------------------------------------------------------------------------------------ suggesting

    /**
     * The purchase orders an answer proposed, one suggestion each, written in the transaction that stores the answer:
     * a proposal shown with an answer always has its suggestion. Each supersedes its owner's open suggestions for the
     * same supplier that it orders all of (the class's javadoc).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void suggestPurchaseOrders(UUID ownerUserId, UUID conversationId, UUID messageId,
                                      List<AnswerSources.Proposal> proposals) {
        Instant now = clock.instant();
        List<AssistantAction> suggested = new java.util.ArrayList<>();
        for (AnswerSources.Proposal proposal : proposals) {
            if (!PURCHASE_ORDER_PROPOSAL.equals(proposal.type())) {
                log.warn("Assistant proposal of type {} has no confirmation; not suggested", proposal.type());
                continue;
            }
            AssistantAction action = AssistantAction.suggest(Identifiers.newId(), ownerUserId, conversationId,
                    messageId, AssistantAction.Kind.PURCHASE_ORDER_DRAFT, proposal.key(), write(proposal), now);
            int superseded = actions.supersedeCovered(ownerUserId, action.kind(), action.proposalKey(),
                    skusOf(proposal), now);
            for (int i = 0; i < superseded; i++) {
                telemetry.action(action.kind().name(), "superseded", false);
            }
            suggested.add(action);
        }
        if (suggested.isEmpty()) {
            return;
        }
        actions.suggestAll(suggested);
        suggested.forEach(action -> telemetry.action(action.kind().name(), "suggested", false));
    }

    /**
     * The goods receipt a delivery note's photo was read into, for its reader to check and confirm; nothing when no
     * line of the order was read, since there is nothing to confirm. It supersedes nothing (the class's javadoc).
     */
    @Transactional
    public java.util.Optional<ActionView> suggestGoodsReceipt(CurrentUser user, ReceiptDraft draft) {
        if (draft.lines().isEmpty()) {
            return java.util.Optional.empty();
        }
        Instant now = clock.instant();
        AssistantAction action = actions.save(AssistantAction.suggest(Identifiers.newId(), user.userId(), null, null,
                AssistantAction.Kind.GOODS_RECEIPT_DRAFT, receiptKey(draft.poNumber()), write(draft), now));
        telemetry.action(action.kind().name(), "suggested", false);
        return java.util.Optional.of(view(action, now));
    }

    /** What a receipt draft proposes: a receipt against this purchase order. */
    static String receiptKey(String poNumber) {
        return AssistantAction.Kind.GOODS_RECEIPT_DRAFT.name() + ":" + poNumber.strip().toUpperCase(Locale.ROOT);
    }

    private static Set<String> skusOf(AnswerSources.Proposal proposal) {
        return proposal.lines().stream().map(line -> line.sku().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ActionView> mine(CurrentUser user, boolean openOnly, int page, int size) {
        Instant now = clock.instant();
        PageResponse<AssistantAction> found = actions.mine(user.userId(), openOnly, now, page, size);
        return PageResponse.of(found.items().stream().map(action -> view(action, now)).toList(), found.page(),
                found.size(), found.totalElements());
    }

    /** @throws NotFoundException when there is no such suggestion of the caller's */
    @Transactional(readOnly = true)
    public ActionView get(UUID actionId, CurrentUser user) {
        return view(actions.find(actionId, user.userId()).orElseThrow(() -> notFound(actionId)), clock.instant());
    }

    /** The suggestions of the given answers, by answer and proposal ({@link AnswerSources.Proposal#key}). */
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, ActionView>> ofAnswers(Collection<UUID> messageIds, UUID ownerUserId) {
        Instant now = clock.instant();
        Map<UUID, Map<String, ActionView>> byAnswer = new HashMap<>();
        for (AssistantAction action : actions.ofMessages(messageIds, ownerUserId)) {
            byAnswer.computeIfAbsent(action.messageId(), message -> new HashMap<>())
                    .put(action.proposalKey(), view(action, now));
        }
        return byAnswer;
    }

    // ------------------------------------------------------------------------------------------------ deciding

    /**
     * Creates the DRAFT purchase order its owner confirmed, from the supplier's current catalog. Called under the
     * purchase-order screen's own guard ({@code procurement-purchase-orders} CREATE): its data scope is the one
     * {@code createPurchaseOrder} checks the warehouse against.
     *
     * @throws NotFoundException when the suggestion is not the caller's, or not a purchase order
     * @throws BusinessException ASSISTANT_ACTION_EXPIRED, ASSISTANT_ACTION_CLOSED, VALIDATION_FAILED (a line that was
     *                           not suggested), and whatever {@code createPurchaseOrder} refuses
     */
    @Transactional
    public ActionView confirmPurchaseOrder(UUID actionId, CurrentUser user, PurchaseOrderChoice choice) {
        AssistantAction action = locked(actionId, user, AssistantAction.Kind.PURCHASE_ORDER_DRAFT);
        Instant now = clock.instant();
        if (action.storedStatus() == AssistantAction.Status.CONFIRMED) {
            return view(action, now);
        }
        action.requireOpen(now);
        AnswerSources.Proposal proposal = read(action.suggested(), AnswerSources.Proposal.class);
        Map<String, Integer> lines = chosenLines(choice, proposal);
        if (choice.warehouseId() == null) {
            throw invalid("warehouseId", "the warehouse the goods are received at is required");
        }
        SkuSupplierSummary supplier = supplierOf(lines.keySet().iterator().next(), proposal.supplierCode());
        PurchaseOrderSummary order;
        try {
            order = procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier.supplierId(),
                    choice.warehouseId(), supplier.currency(), choice.expectedAt(), blankToNull(choice.note()),
                    lines.entrySet().stream()
                            .map(line -> new CreatePOLineCommand(line.getKey(), null, line.getValue(), null))
                            .toList()));
        } catch (BusinessException refused) {
            telemetry.action(action.kind().name(), "refused", false);
            throw refused;
        }
        action.confirm(write(choice), order.purchaseOrderId(), order.poNumber(), now);
        AssistantAction saved = actions.save(action);
        telemetry.action(action.kind().name(), "confirmed", edited(proposal, lines));
        return view(saved, now).withPossibleDuplicate(order.possibleDuplicate());
    }

    /**
     * Creates the DRAFT goods receipt its owner confirmed, its lines counted, against the purchase order the photo
     * was read for. Called under the receipt screen's guard ({@code procurement-goods-receipts} CREATE); counting
     * needs that screen's UPDATE too.
     *
     * @throws NotFoundException when the suggestion is not the caller's, or not a goods receipt
     * @throws BusinessException ASSISTANT_ACTION_EXPIRED, ASSISTANT_ACTION_CLOSED, VALIDATION_FAILED (a line of no
     *                           line of the order), and whatever the receipt screen refuses
     */
    @Transactional
    public ActionView confirmGoodsReceipt(UUID actionId, CurrentUser user, GoodsReceiptChoice choice) {
        user.require(GOODS_RECEIPTS, Action.UPDATE);
        AssistantAction action = locked(actionId, user, AssistantAction.Kind.GOODS_RECEIPT_DRAFT);
        Instant now = clock.instant();
        if (action.storedStatus() == AssistantAction.Status.CONFIRMED) {
            return view(action, now);
        }
        action.requireOpen(now);
        ReceiptDraft draft = read(action.suggested(), ReceiptDraft.class);
        if (choice.lines() == null || choice.lines().isEmpty()) {
            throw invalid("lines", "a receipt counts at least one line");
        }
        PurchaseOrderSummary order = receiptDrafts.receivable(draft.poNumber());
        Map<Integer, UUID> lineIds = order.lines().stream()
                .collect(Collectors.toMap(POLineSummary::lineNo, POLineSummary::lineId, (first, second) -> first));
        List<GoodsReceiptDrafting.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < choice.lines().size(); i++) {
            ReceiptLine line = choice.lines().get(i);
            UUID lineId = lineIds.get(line.poLineNo());
            if (lineId == null) {
                throw invalid("lines[" + i + "].poLineNo", order.poNumber() + " has no line " + line.poLineNo());
            }
            String location = blankToNull(line.locationCode()) == null ? blankToNull(choice.locationCode())
                    : blankToNull(line.locationCode());
            if (location == null) {
                throw invalid("lines[" + i + "].locationCode", "the receiving area the line is put in is required");
            }
            lines.add(new GoodsReceiptDrafting.Line(lineId, line.quantity(), blankToNull(line.lotNumber()),
                    line.expiryDate(), location, blankToNull(line.note())));
        }
        GoodsReceiptDrafting.DraftedReceipt receipt;
        try {
            receipt = receipts.draft(new GoodsReceiptDrafting.DraftReceiptCommand(order.purchaseOrderId(),
                    blankToNull(choice.deliveryNote()), blankToNull(choice.note()), lines), user.userId());
        } catch (BusinessException refused) {
            telemetry.action(action.kind().name(), "refused", false);
            throw refused;
        }
        boolean another = actions.confirmedSince(action.ownerUserId(), action.kind(), action.proposalKey(),
                now.minus(AssistantAction.OPEN_FOR), action.id());
        action.confirm(write(choice), receipt.receiptId(), receipt.receiptNumber(), now);
        AssistantAction saved = actions.save(action);
        telemetry.action(action.kind().name(), "confirmed", edited(draft, choice));
        return view(saved, now).withPossibleDuplicate(another);
    }

    /**
     * Its owner does not want it: open or expired, it is dismissed; dismissed already, nothing changes.
     *
     * @throws BusinessException ASSISTANT_ACTION_CLOSED when it was confirmed
     */
    @Transactional
    public ActionView dismiss(UUID actionId, CurrentUser user) {
        AssistantAction action = actions.findForUpdate(actionId, user.userId())
                .orElseThrow(() -> notFound(actionId));
        Instant now = clock.instant();
        boolean open = action.storedStatus() == AssistantAction.Status.SUGGESTED;
        action.dismiss(now);
        AssistantAction saved = actions.save(action);
        if (open) {
            telemetry.action(action.kind().name(), "dismissed", false);
        }
        return view(saved, now);
    }

    /** @see AssistantActionRepository#deleteStandaloneBefore */
    @Transactional
    public int deleteStandaloneBefore(Instant cutoff, int limit) {
        return actions.deleteStandaloneBefore(cutoff, limit);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private AssistantAction locked(UUID actionId, CurrentUser user, AssistantAction.Kind kind) {
        AssistantAction action = actions.findForUpdate(actionId, user.userId())
                .orElseThrow(() -> notFound(actionId));
        if (action.kind() != kind) {
            throw notFound(actionId);
        }
        return action;
    }

    /**
     * The lines confirmed, by SKU: each one the proposal suggested, once, with a positive quantity. A line may be
     * dropped or its quantity changed; a SKU the assistant did not suggest is added on the purchase-order screen.
     */
    private static Map<String, Integer> chosenLines(PurchaseOrderChoice choice, AnswerSources.Proposal proposal) {
        if (choice.lines() == null || choice.lines().isEmpty()) {
            throw invalid("lines", "a purchase order orders at least one line");
        }
        Set<String> suggested = proposal.lines().stream().map(line -> line.sku().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> chosen = new LinkedHashMap<>();
        for (int i = 0; i < choice.lines().size(); i++) {
            OrderLine line = choice.lines().get(i);
            String sku = line.sku() == null ? "" : line.sku().strip().toUpperCase(Locale.ROOT);
            if (!suggested.contains(sku)) {
                throw invalid("lines[" + i + "].sku", "the assistant did not suggest " + sku + " for this order");
            }
            if (line.quantity() <= 0) {
                throw invalid("lines[" + i + "].quantity", "a quantity is a positive whole number");
            }
            if (chosen.putIfAbsent(sku, line.quantity()) != null) {
                throw invalid("lines[" + i + "].sku", sku + " is listed twice");
            }
        }
        return chosen;
    }

    /** The supplier of the proposal as it sells {@code sku} today: its id and the currency of its catalog prices. */
    private SkuSupplierSummary supplierOf(String sku, String supplierCode) {
        return supplierItems.suppliersOf(sku).stream()
                .filter(candidate -> candidate.supplierCode().equalsIgnoreCase(supplierCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "Supplier " + supplierCode + " no longer sells " + sku + "; ask the assistant again"));
    }

    private static boolean edited(AnswerSources.Proposal proposal, Map<String, Integer> chosen) {
        Map<String, Integer> suggested = proposal.lines().stream().collect(Collectors.toMap(
                line -> line.sku().toUpperCase(Locale.ROOT), line -> (int) line.quantity(), (first, second) -> first));
        return !suggested.equals(chosen);
    }

    private static boolean edited(ReceiptDraft draft, GoodsReceiptChoice choice) {
        Map<Integer, Integer> suggested = draft.lines().stream().filter(line -> line.proposedQuantity() != null)
                .collect(Collectors.toMap(ReceiptDraft.Line::poLineNo, ReceiptDraft.Line::proposedQuantity,
                        Integer::sum));
        Map<Integer, Integer> chosen = choice.lines().stream().collect(Collectors.toMap(ReceiptLine::poLineNo,
                ReceiptLine::quantity, Integer::sum));
        return !suggested.equals(chosen);
    }

    private ActionView view(AssistantAction action, Instant now) {
        return new ActionView(action.id(), action.kind(), action.statusAt(now), action.createdAt(),
                action.expiresAt(), action.decidedAt(), action.resultId(), action.resultNumber(),
                tree(action.suggested()), action.submitted() == null ? null : tree(action.submitted()), null);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a suggestion could not be written as JSON", impossible);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("a stored suggestion could not be read", unreadable);
        }
    }

    private JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("a stored suggestion could not be read", unreadable);
        }
    }

    private static NotFoundException notFound(UUID actionId) {
        return new NotFoundException("AssistantAction", actionId);
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, field + ": " + message);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
