package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a photographed delivery note into a draft goods receipt (design §8.1, FEAT-10,
 * {@code POST /api/v1/assistant/receipt-drafts}): the person counting the delivery checks it and enters it on the
 * goods-receipt screen. ADR-0010's rule holds: a proposal, never a write. This service stores nothing, the photo
 * included; the controller keeps the draft it returns as a suggestion of the reader's (ADR-0011), and with it what was
 * read off the paper (each line's text, the supplier's name) until the suggestion is deleted (design §8.2).
 *
 * <p><b>Refused before anything leaves.</b> The order is read through {@code procurement :: api} under the caller's
 * own data scope, which already keeps a warehouse-limited user to the orders of their warehouses, and the receipt's
 * own rule is applied again by name (BR-SEC-002, {@link WarehouseScope#requireWarehouse}): goods are received by the
 * staff of the warehouse the order delivers to, so a photo is never sent for an order its sender could not receive.
 * An order nothing can be received against (BR-01: not CONFIRMED or PARTIALLY_RECEIVED, refused by the supplier, no
 * line left open) is refused next, and a switched-off assistant after that: the photo is decoded, re-encoded and sent
 * only once all of it has passed.</p>
 *
 * <p><b>What the provider receives</b> (design §5.5, AQ-01): the instruction, the order's number and supplier name,
 * and for each open line its number, SKU, product name (masked like any typed text), quantities and unit; and the
 * photo, re-encoded without its metadata ({@link DocumentImageProcessor}). The photo shows whatever the paper shows:
 * prices, tax codes, the supplier's address and a signature are sent with it. The log has sizes and outcomes only.</p>
 *
 * <p><b>Not a question.</b> A reading does not count against the daily question cap, which measures conversation;
 * the per-user rate limit of the questions applies to it (the controller's), since each reading is a model call.</p>
 *
 * <p><b>Not transactional.</b> The two reads of the order open and close their own transactions; the model call that
 * follows takes seconds and must not hold a pooled connection.</p>
 */
@Service
public class ReceiptDraftService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptDraftService.class);

    static final String INSTRUCTION_RESOURCE = "assistant/delivery-note-prompt.txt";

    /** Said before the order's records, so the model tells them from what it reads off the photo. */
    static final String CONTEXT_LEAD = "The purchase order this delivery should be for, from the system's records "
            + "(not from the photograph): ";

    /** The schema of the answer: the order of the properties is the order the model writes them in. */
    static final Map<String, Object> SCHEMA = schema();

    /** The longest order number accepted: far above any the business issues. */
    static final int MAX_PO_NUMBER = 100;

    /**
     * Orders the typed number may match as text before the exact one is picked: the list matches part of the number,
     * and numbers of one day share a prefix.
     */
    private static final int CANDIDATES = 50;

    /** BR-01: the statuses goods are received under. */
    private static final Set<String> RECEIVABLE = Set.of("CONFIRMED", "PARTIALLY_RECEIVED");

    private static final Set<String> OPEN_LINE = Set.of("OPEN", "PARTIALLY_RECEIVED");

    private final ProcurementService procurement;
    private final InventoryControlService inventoryControl;
    private final DocumentReader reader;
    private final DocumentImageProcessor images;
    private final Clock clock;
    private final String instruction;
    private final ObjectMapper json = new ObjectMapper();

    ReceiptDraftService(ProcurementService procurement, InventoryControlService inventoryControl,
                        DocumentReader reader, DocumentImageProcessor images, Clock clock) {
        this.procurement = procurement;
        this.inventoryControl = inventoryControl;
        this.reader = reader;
        this.images = images;
        this.clock = clock;
        this.instruction = instruction();
    }

    /**
     * The draft of a receipt against {@code poNumber}, read from the photo.
     *
     * @throws BusinessException {@code VALIDATION_FAILED}, {@code PAYLOAD_TOO_LARGE} or {@code UNSUPPORTED_MEDIA_TYPE}
     *         for the request; {@code PURCHASE_ORDER_NOT_FOUND} for a number the caller has no order of (another
     *         warehouse's included); {@code OUT_OF_DATA_SCOPE}; {@code PURCHASE_ORDER_NOT_RECEIVABLE};
     *         {@code ASSISTANT_DISABLED} and the reader's {@code ASSISTANT_*} codes
     */
    public ReceiptDraft draft(String poNumber, FileUpload upload) {
        long started = System.nanoTime();
        String number = number(poNumber);
        byte[] photo = images.accept(upload);
        PurchaseOrderSummary order = order(number);
        WarehouseScope.requireWarehouse(order.warehouseId());
        List<POLineSummary> open = openLines(order);
        if (!reader.available()) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }

        DocumentImageProcessor.Prepared image = images.prepare(photo);
        DocumentReader.Reading reading = reader.read(new DocumentReader.Request(instruction, context(order, open),
                image.image(), SCHEMA));
        Parsed parsed = parse(reading);
        ReceiptDraft draft = ReceiptDraft.of(order.poNumber(), order.supplierName(), orderLines(open), parsed.lines(),
                new ReceiptDraft.Rules(order.tolerancePercent(), BusinessDate.of(clock.instant()),
                        inventoryControl.expiryWarningDays()));
        log.info("Delivery note for {} read by {}: {}{}; photo {} bytes, sent {} bytes at {}x{}; {} line(s) read, {} "
                        + "proposed, {} unmatched; {} input and {} output tokens; {} ms", order.poNumber(),
                reading.model(), reading.outcome(), parsed.understood() ? "" : " (answer not of the schema)",
                photo.length, image.image().bytes().length, image.width(), image.height(), parsed.lines().size(),
                draft.lines().size(), draft.unmatched().size(), reading.usage().inputTokens(),
                reading.usage().outputTokens(), (System.nanoTime() - started) / 1_000_000);
        return draft;
    }

    /**
     * The order {@code poNumber} names, with its lines, as the draft read it: the caller's own list, warehouse limit
     * applied. For confirming a draft against the same order it was read for.
     *
     * @throws BusinessException PURCHASE_ORDER_NOT_FOUND, VALIDATION_FAILED
     */
    PurchaseOrderSummary receivable(String poNumber) {
        PurchaseOrderSummary order = order(number(poNumber));
        WarehouseScope.requireWarehouse(order.warehouseId());
        return order;
    }

    private static String number(String poNumber) {
        String number = poNumber == null ? "" : poNumber.strip();
        if (number.isEmpty() || number.length() > MAX_PO_NUMBER || number.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "poNumber is the number of the purchase order the delivery is for, at most " + MAX_PO_NUMBER
                            + " characters");
        }
        return number;
    }

    /**
     * The order with that exact number, read as its detail screen reads it. The list is the caller's own, warehouse
     * limit applied, so an order of another warehouse is not found: the answer says nothing about whether it exists.
     */
    private PurchaseOrderSummary order(String number) {
        UUID id = procurement.list(new ListPurchaseOrdersQuery(0, CANDIDATES, null, null, null, number, null))
                .items().stream()
                .filter(order -> number.equalsIgnoreCase(order.poNumber()))
                .map(PurchaseOrderSummary::purchaseOrderId)
                .findFirst()
                .orElseThrow(() -> notFound(number));
        return procurement.findById(id).orElseThrow(() -> notFound(number));
    }

    private static BusinessException notFound(String number) {
        return new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + number);
    }

    /** The lines goods may still be received on, once the order itself may be received against (BR-01). */
    private static List<POLineSummary> openLines(PurchaseOrderSummary order) {
        if (!RECEIVABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE, ("%s is %s; goods are received only "
                    + "against a CONFIRMED or PARTIALLY_RECEIVED order (BR-01)").formatted(order.poNumber(),
                    order.status()));
        }
        if ("REJECTED".equals(order.supplierConfirmationStatus())) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE,
                    "%s was refused by the supplier; nothing is received against it (BR-01)".formatted(order.poNumber()));
        }
        List<POLineSummary> open = order.lines().stream().filter(line -> OPEN_LINE.contains(line.status())).toList();
        if (open.isEmpty()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE,
                    "%s has no line left to receive".formatted(order.poNumber()));
        }
        return open;
    }

    /**
     * The order's records for the model, as JSON after a lead sentence; product names masked as typed text. Not the
     * quantities ordered or still to receive: given them, a model reading a smudged figure writes the expected one, and
     * the draft then agrees with the order instead of with the paper. Over-delivery is the draft's own check.
     */
    String context(PurchaseOrderSummary order, List<POLineSummary> open) {
        ObjectNode records = JsonNodeFactory.instance.objectNode();
        records.put("purchaseOrder", order.poNumber());
        records.put("supplier", order.supplierName());
        ArrayNode lines = records.putArray("openLines");
        for (POLineSummary line : open) {
            ObjectNode entry = lines.addObject()
                    .put("lineNo", line.lineNo())
                    .put("sku", line.sku())
                    .put("product", TypedText.maskedContacts(line.description()))
                    .put("unit", line.uom());
            // A line ordered in packs: a note that counts cartons can then be read in units (the instruction says how).
            if (line.unitFactor() != null && line.unitFactor() > 1 && line.unitOfMeasure() != null) {
                entry.put("packingUnit", line.unitOfMeasure()).put("unitsPerPacking", line.unitFactor());
            }
        }
        try {
            return CONTEXT_LEAD + json.writeValueAsString(records);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a JSON tree always serialises", impossible);
        }
    }

    private static List<ReceiptDraft.OrderLine> orderLines(List<POLineSummary> open) {
        return open.stream().map(line -> new ReceiptDraft.OrderLine(line.lineNo(), line.sku(), line.quantityOrdered(),
                line.quantityReceived(), line.lotTracked(), line.expiryTracked())).toList();
    }

    /**
     * What was read, and whether the answer was JSON of the schema at all.
     *
     * @param understood false when an answer came back that is not an object with a {@code lines} array
     */
    record Parsed(List<ReceiptDraft.ReadLine> lines, boolean understood) {
    }

    /**
     * The answer's lines, value by value: a value of the wrong JSON type is null, never coerced. An answer that is
     * not JSON of the schema reads as nothing; {@link ReceiptDraft} says so to the person.
     */
    Parsed parse(DocumentReader.Reading reading) {
        if (reading.outcome() != DocumentReader.Outcome.READ) {
            return new Parsed(List.of(), true);
        }
        JsonNode lines;
        try {
            lines = json.readTree(unfenced(reading.json())).path("lines");
        } catch (JsonProcessingException notJson) {
            return new Parsed(List.of(), false);
        }
        if (!lines.isArray()) {
            return new Parsed(List.of(), false);
        }
        List<ReceiptDraft.ReadLine> read = new ArrayList<>();
        for (JsonNode line : lines) {
            if (line.isObject()) {
                read.add(new ReceiptDraft.ReadLine(integer(line.path("poLineNo")), integer(line.path("quantity")),
                        text(line.path("lotNumber")), text(line.path("expiryDate")), text(line.path("readText"))));
            }
        }
        return new Parsed(read, true);
    }

    /** A whole number of the JSON, or its plain digits as a string; null for anything else, 1.200 and 12.5 included. */
    static Integer integer(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isTextual() && node.textValue().strip().matches("\\d{1,9}")) {
            return Integer.valueOf(node.textValue().strip());
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : null;
    }

    /** The answer without the Markdown fence a model may put round JSON when nothing constrains it. */
    static String unfenced(String answer) {
        String text = answer.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLine = text.indexOf('\n');
        text = firstLine < 0 ? "" : text.substring(firstLine + 1);
        return text.endsWith("```") ? text.substring(0, text.length() - 3).strip() : text;
    }

    private static String instruction() {
        try (InputStream in = new ClassPathResource(INSTRUCTION_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException missing) {
            throw new UncheckedIOException("classpath:" + INSTRUCTION_RESOURCE + " is missing", missing);
        }
    }

    /**
     * {@code {lines: [{readText, poLineNo, quantity, lotNumber, expiryDate}]}}, every property required: the subset
     * Claude's structured outputs and Gemini's response schema both enforce. {@code readText} first, so the model
     * writes down what it read before it decides what that is. Empty strings and 0 stand for "none", rather than
     * nullable types, which the two providers spell differently.
     */
    private static Map<String, Object> schema() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("readText", property("string", "The line as printed, at most 300 characters"));
        line.put("poLineNo", property("integer",
                "lineNo of the open purchase-order line these goods are; 0 when none or more than one matches"));
        line.put("quantity", property("integer", "Units delivered, in the unit of that line; 0 when unreadable"));
        line.put("lotNumber", property("string", "The lot or batch number exactly as printed; empty when none"));
        line.put("expiryDate", property("string", "The expiry date as YYYY-MM-DD; empty when none or uncertain"));
        Map<String, Object> lines = new LinkedHashMap<>();
        lines.put("type", "array");
        lines.put("description", "Each goods line of the delivery note, top to bottom, at most 100");
        lines.put("items", object(line));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lines", Collections.unmodifiableMap(lines));
        return object(root);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("properties", Collections.unmodifiableMap(properties));
        object.put("required", List.copyOf(properties.keySet()));
        object.put("additionalProperties", false);
        return Collections.unmodifiableMap(object);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return Collections.unmodifiableMap(property);
    }
}
