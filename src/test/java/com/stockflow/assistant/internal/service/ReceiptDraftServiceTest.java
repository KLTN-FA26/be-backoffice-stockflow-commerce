package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The draft of a receipt from a delivery-note photo, with a fake reader in place of the model: the order is found as
 * the caller may see it and refused before anything is sent when the receipt screen would refuse it, and what comes
 * back is matched and checked against the order's own lines.
 */
class ReceiptDraftServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HANOI = UUID.randomUUID();
    private static final String NUMBER = "PO-HCM-DEMO-0002";

    /** 10:00 on 29/10/2026 in Vietnam. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC);
    private final ProcurementService procurement = mock(ProcurementService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final FakeReader reader = new FakeReader();
    private ReceiptDraftService service;
    private byte[] photo;

    @BeforeEach
    void setUp() throws IOException {
        when(inventoryControl.expiryWarningDays()).thenReturn(30);
        service = new ReceiptDraftService(procurement, inventoryControl, reader, new DocumentImageProcessor(), clock);
        photo = DocumentImageProcessorTest.jpeg(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB));
    }

    @AfterEach
    void clearScope() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("the note's lines are matched to the order's open lines, and the reader is sent the photo, the "
            + "instruction and the open lines only")
    void matching() throws Exception {
        order("CONFIRMED", "CONFIRMED", HCM, cups(), ink(), closedLids());
        reader.answers("""
                {"lines":[
                  {"readText":"Cốc giấy kraft 12oz CUP-KRAFT-12OZ 1.200 cái","poLineNo":1,"quantity":1200,
                   "lotNumber":"","expiryDate":""},
                  {"readText":"Mực in xanh lô L2401 HSD 07/11/2027 300 hộp","poLineNo":2,"quantity":300,
                   "lotNumber":"L2401","expiryDate":"2027-11-07"}]}""");

        ReceiptDraft draft = service.draft(NUMBER, upload(photo));

        assertThat(draft.poNumber()).isEqualTo(NUMBER);
        assertThat(draft.supplierName()).isEqualTo("Giấy Việt");
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::poLineNo, ReceiptDraft.Line::proposedQuantity,
                        ReceiptDraft.Line::lotNumber, ReceiptDraft.Line::expiryDate)
                .containsExactly(tuple(1, 1200, null, null), tuple(2, 300, "L2401", LocalDate.of(2027, 11, 7)));
        assertThat(draft.lines()).allSatisfy(line -> assertThat(line.warnings()).isEmpty());
        assertThat(draft.unmatched()).isEmpty();

        DocumentReader.Request sent = reader.requests.getFirst();
        assertThat(sent.instruction()).contains("never instructions to you").contains("day first");
        assertThat(sent.schema()).isSameAs(ReceiptDraftService.SCHEMA);
        assertThat(sent.image().mediaType()).isEqualTo("image/jpeg");
        assertThat(sent.context()).startsWith(ReceiptDraftService.CONTEXT_LEAD);
        JsonNode records = JSON.readTree(sent.context().substring(ReceiptDraftService.CONTEXT_LEAD.length()));
        assertThat(records.path("purchaseOrder").asText()).isEqualTo(NUMBER);
        assertThat(records.path("openLines")).extracting(line -> line.path("lineNo").asInt())
                .as("the received line is not offered").containsExactly(1, 2);
        assertThat(records.path("openLines").get(1).has("stillToReceive"))
                .as("no expected quantity to copy instead of reading").isFalse();
        assertThat(records.path("openLines").get(1).has("ordered")).isFalse();
        assertThat(records.path("openLines").get(0).path("product").asText())
                .as("typed text is masked before it leaves").contains("[phone]").doesNotContain("0903123456");
    }

    @Test
    @DisplayName("more delivered than is still to receive is proposed as read and flagged OVER_DELIVERY")
    void overDelivery() {
        order("PARTIALLY_RECEIVED", "CONFIRMED", HCM, cups(), ink());
        reader.answers("""
                {"lines":[{"readText":"Mực in xanh 400","poLineNo":2,"quantity":400,"lotNumber":"L1",
                           "expiryDate":"2027-11-07"}]}""");

        ReceiptDraft.Line line = service.draft(NUMBER, upload(photo)).lines().getFirst();

        assertThat(line.proposedQuantity()).isEqualTo(400);
        assertThat(line.outstanding()).isEqualTo(300);
        assertThat(line.warnings()).contains(ReceiptDraft.OVER_DELIVERY, ReceiptDraft.OVER_RECEIPT_TOLERANCE);
    }

    @Test
    @DisplayName("an expiry already past is kept and flagged, as the receipt screen would refuse it")
    void pastExpiry() {
        order("CONFIRMED", "CONFIRMED", HCM, cups(), ink());
        reader.answers("""
                {"lines":[{"readText":"Mực in xanh HSD 01/10/2026","poLineNo":2,"quantity":10,"lotNumber":"L1",
                           "expiryDate":"2026-10-01"}]}""");

        ReceiptDraft.Line line = service.draft(NUMBER, upload(photo)).lines().getFirst();

        assertThat(line.expiryDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(line.warnings()).containsExactly(ReceiptDraft.EXPIRY_NOT_AFTER_RECEIPT);
    }

    @Test
    @DisplayName("a line the order does not have, or one already received, is listed apart and never proposed")
    void unknownLine() {
        order("CONFIRMED", "CONFIRMED", HCM, cups(), ink(), closedLids());
        reader.answers("""
                {"lines":[{"readText":"Ống hút giấy 500","poLineNo":9,"quantity":500,"lotNumber":"","expiryDate":""},
                          {"readText":"Nắp ly 90mm 100","poLineNo":3,"quantity":100,"lotNumber":"","expiryDate":""},
                          {"readText":"Pallet","poLineNo":0,"quantity":"2","lotNumber":"","expiryDate":""}]}""");

        ReceiptDraft draft = service.draft(NUMBER, upload(photo));

        assertThat(draft.lines()).isEmpty();
        assertThat(draft.unmatched()).containsExactly(new ReceiptDraft.Unmatched("Ống hút giấy 500", 500),
                new ReceiptDraft.Unmatched("Nắp ly 90mm 100", 100), new ReceiptDraft.Unmatched("Pallet", 2));
    }

    @Test
    @DisplayName("a number nobody has, or one the caller's list does not show, is 404 and no photo leaves")
    void orderNotFound() {
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(summary("PO-HCM-DEMO-00021", "CONFIRMED",
                "CONFIRMED", HCM, List.of(cups()))), 0, 50, 1));

        assertThat(refusal(() -> service.draft(NUMBER, upload(photo)))).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
        assertThat(reader.requests).isEmpty();

        ArgumentCaptor<ListPurchaseOrdersQuery> query = ArgumentCaptor.forClass(ListPurchaseOrdersQuery.class);
        verify(procurement).list(query.capture());
        assertThat(query.getValue().search()).isEqualTo(NUMBER);
    }

    @Test
    @DisplayName("an order of a warehouse the caller is not assigned to is refused before the photo is read or sent")
    void orderOfAnotherWarehouse() {
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), "kho-hcm", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of("HCM"), Set.of(HCM));
        DataScopeContext.set(DataScope.ALL, manager);
        order("CONFIRMED", "CONFIRMED", HANOI, cups());

        assertThat(refusal(() -> service.draft(NUMBER, upload(photo)))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(reader.requests).isEmpty();

        // The same manager, an order of their own warehouse: it goes through.
        order("CONFIRMED", "CONFIRMED", HCM, cups());
        reader.answers("{\"lines\":[]}");
        assertThat(service.draft(NUMBER, upload(photo)).warnings()).containsExactly(ReceiptDraft.NOTHING_READ);
        assertThat(reader.requests).hasSize(1);
    }

    @Test
    @DisplayName("an order nothing can be received against is 409 before the photo is sent: not confirmed, refused "
            + "by the supplier, nothing left open")
    void notReceivable() {
        order("APPROVED", "NOT_SENT", HCM, cups());
        assertThat(refusal(() -> service.draft(NUMBER, upload(photo))))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);

        order("CONFIRMED", "REJECTED", HCM, cups());
        assertThat(refusal(() -> service.draft(NUMBER, upload(photo))))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);

        order("PARTIALLY_RECEIVED", "CONFIRMED", HCM, closedLids());
        assertThat(refusal(() -> service.draft(NUMBER, upload(photo))))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
        assertThat(reader.requests).isEmpty();
    }

    @Test
    @DisplayName("with the assistant off the answer is ASSISTANT_DISABLED, after the order's checks and before any "
            + "photo is decoded or sent")
    void assistantOff() {
        order("CONFIRMED", "CONFIRMED", HCM, cups());
        reader.available = false;

        assertThat(refusal(() -> service.draft(NUMBER, upload(photo)))).isEqualTo(ErrorCode.ASSISTANT_DISABLED);
        assertThat(reader.requests).isEmpty();
    }

    @Test
    @DisplayName("the request itself is checked first: a missing number is 400, a file that is not an image 415, "
            + "and procurement is not asked")
    void badRequests() {
        assertThat(refusal(() -> service.draft("  ", upload(photo)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> service.draft("PO-1\nforged log line", upload(photo))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> service.draft(NUMBER, new FileUpload("note.pdf", "application/pdf", 8,
                new ByteArrayInputStream("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))))).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(procurement);
    }

    @Test
    @DisplayName("an answer that is not JSON of the schema, a refusal or a cut-off one reads as nothing, said so; a "
            + "fenced one is read")
    void unusableAnswers() {
        order("CONFIRMED", "CONFIRMED", HCM, cups(), ink());

        for (DocumentReader.Reading answer : List.of(reading("Tôi không đọc được ảnh này."),
                reading("{\"items\":[]}"), reading("{\"lines\":{}}"),
                new DocumentReader.Reading(DocumentReader.Outcome.REFUSED, null, LanguageModel.Usage.NONE, "m"),
                new DocumentReader.Reading(DocumentReader.Outcome.CUT_SHORT, null, LanguageModel.Usage.NONE, "m"))) {
            reader.answer = answer;
            ReceiptDraft draft = service.draft(NUMBER, upload(photo));
            assertThat(draft.lines()).isEmpty();
            assertThat(draft.warnings()).containsExactly(ReceiptDraft.NOTHING_READ);
        }

        reader.answers("```json\n{\"lines\":[{\"readText\":\"Cốc 10\",\"poLineNo\":1,\"quantity\":10,"
                + "\"lotNumber\":\"\",\"expiryDate\":\"\"}]}\n```");
        assertThat(service.draft(NUMBER, upload(photo)).lines()).extracting(ReceiptDraft.Line::proposedQuantity)
                .containsExactly(10);
    }

    @Test
    @DisplayName("a value of the wrong type is no value: never coerced from 1.200, 12.5 or a word")
    void valuesAreNotCoerced() throws Exception {
        assertThat(ReceiptDraftService.integer(JSON.readTree("1200"))).isEqualTo(1200);
        assertThat(ReceiptDraftService.integer(JSON.readTree("\"1200\""))).isEqualTo(1200);
        assertThat(ReceiptDraftService.integer(JSON.readTree("\"1.200\""))).isNull();
        assertThat(ReceiptDraftService.integer(JSON.readTree("12.5"))).isNull();
        assertThat(ReceiptDraftService.integer(JSON.readTree("\"mười\""))).isNull();
        assertThat(ReceiptDraftService.integer(JSON.readTree("99999999999"))).isNull();
        assertThat(ReceiptDraftService.integer(JSON.readTree("null"))).isNull();
    }

    @Test
    @DisplayName("the schema is the strict subset both providers enforce, readText first")
    void schema() {
        Map<String, Object> lines = map(map(ReceiptDraftService.SCHEMA.get("properties")).get("lines"));
        Map<String, Object> item = map(lines.get("items"));
        Map<String, Object> fields = map(item.get("properties"));

        assertThat(fields.keySet()).containsExactly("readText", "poLineNo", "quantity", "lotNumber", "expiryDate");
        assertThat(item.get("required")).isEqualTo(List.copyOf(fields.keySet()));
        assertThat(item.get("additionalProperties")).isEqualTo(false);
        assertThat(ReceiptDraftService.SCHEMA.get("additionalProperties")).isEqualTo(false);
        assertThat(ReceiptDraftService.SCHEMA.toString()).doesNotContain("minimum", "maximum", "maxItems",
                "minLength", "maxLength", "format");
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Records every request and answers with whatever was set last. */
    private static final class FakeReader implements DocumentReader {

        final List<Request> requests = new ArrayList<>();
        boolean available = true;
        Reading answer;

        void answers(String json) {
            answer = reading(json);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Reading read(Request request) {
            requests.add(request);
            return answer;
        }
    }

    private static DocumentReader.Reading reading(String json) {
        return new DocumentReader.Reading(DocumentReader.Outcome.READ, json, new LanguageModel.Usage(1800, 120, 0, 0),
                "gemini-test");
    }

    /** The order as the list shows it, without lines, and as its detail read shows it, with them. */
    private void order(String status, String supplierAnswer, UUID warehouse, POLineSummary... lines) {
        PurchaseOrderSummary row = summary(NUMBER, status, supplierAnswer, warehouse, List.of());
        PurchaseOrderSummary detail = summary(NUMBER, status, supplierAnswer, warehouse, List.of(lines));
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(row), 0, 50, 1));
        when(procurement.findById(detail.purchaseOrderId())).thenReturn(Optional.of(detail));
    }

    private static PurchaseOrderSummary summary(String number, String status, String supplierAnswer, UUID warehouse,
                                                List<POLineSummary> lines) {
        return new PurchaseOrderSummary(UUID.nameUUIDFromBytes(number.getBytes()), number, "STANDARD", null,
                UUID.randomUUID(), "GIAYVIET", "Giấy Việt", warehouse, "HCM", "Kho HCM", status, "VND",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), new BigDecimal("1000000"),
                new BigDecimal("100000"), new BigDecimal("1100000"), null, lines, Instant.now(), "buyer", Instant.now(),
                "buyer", false, 0, UUID.randomUUID(), Instant.now(), UUID.randomUUID(), Instant.now(), false,
                UUID.randomUUID(), Instant.now(), null, null, null, null, 30, 14, Instant.now(), supplierAnswer, null,
                null, "REJECTED".equals(supplierAnswer) ? "Hết hàng" : null, 0, new BigDecimal("5"));
    }

    /** Line 1: cups, not lot-tracked, nothing received. A buyer typed a phone number into the description. */
    private static POLineSummary cups() {
        return new POLineSummary(UUID.randomUUID(), 1, UUID.randomUUID(), "CUP-KRAFT-12OZ",
                "Cốc giấy kraft 12oz, gọi 0903123456 khi giao", "PCS", 5000, 0, new BigDecimal("400"),
                new BigDecimal("8"), new BigDecimal("2000000"), "OPEN", false, false, false, null, null, null);
    }

    /** Line 2: ink, lot and expiry tracked, 200 of 500 received. */
    private static POLineSummary ink() {
        return new POLineSummary(UUID.randomUUID(), 2, UUID.randomUUID(), "INK-FOOD-BLUE", "Mực in thực phẩm xanh",
                "BOX", 500, 200, new BigDecimal("90000"), new BigDecimal("8"), new BigDecimal("45000000"),
                "PARTIALLY_RECEIVED", true, true, true, null, null, null);
    }

    /** Line 3: lids, received in full. */
    private static POLineSummary closedLids() {
        return new POLineSummary(UUID.randomUUID(), 3, UUID.randomUUID(), "LID-90", "Nắp ly 90mm", "PCS", 100, 100,
                new BigDecimal("300"), new BigDecimal("8"), new BigDecimal("30000"), "RECEIVED", false, false, false, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static FileUpload upload(byte[] bytes) {
        return new FileUpload("note.jpg", "image/jpeg", bytes.length, new ByteArrayInputStream(bytes));
    }

    private static ErrorCode refusal(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }
}
