package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.ReceiptDraftResponse;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.assistant.internal.service.ReceiptDraftService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * A draft goods receipt read from a photographed delivery note (design §8.1): the goods-receipt screen sends the photo
 * and the purchase order's number, and prefills the receipt it then creates with what comes back.
 *
 * <p><b>The receipt screen's guard, exactly.</b> {@code procurement-goods-receipts:CREATE}, the permission of
 * {@code POST /api/v1/goods-receipts}, which creates the receipt this drafts (resource copied by value, as in
 * {@code ToolPermissions}: procurement's constants are internal to it). Not {@code assistant-conversations}: the photo
 * is part of receiving, so whoever may receive goods may have the note read, the dock clerks included, whatever their
 * role may do in the chat (design §13 AQ-02). A portal customer is refused first, as on every assistant endpoint; the service then
 * applies the receipt's warehouse rule.</p>
 *
 * <p><b>The questions' budget.</b> Each reading is a model call, so it draws on the same per-user rate-limit bucket
 * as a question ({@link AssistantResources#QUESTIONS_BUCKET}); it is not a question, so it does not count against the
 * daily cap.</p>
 *
 * <p>Multipart requests bypass the idempotency filter, as every upload does; a repeated request reads the photo
 * again, and writes nothing either time.</p>
 *
 * <p><b>Off unless asked for.</b> The endpoint exists only with {@value #ENABLED} true (environment variable
 * {@code ASSISTANT_RECEIPT_DRAFTS_ENABLED}; the local profile sets it). The whole photo goes to the model provider,
 * with what the paper carries: the supplier's prices, tax code and address, the deliverer's name and signature. The
 * chat's agreement to send tool results (design §13 AQ-01) does not cover it, so it does not come on with the chat.
 * Without it the path answers 404.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant/receipt-drafts")
@Tag(name = "Assistant")
@ConditionalOnProperty(name = ReceiptDraftController.ENABLED, havingValue = "true")
class ReceiptDraftController {

    /** The property that opens the endpoint when true. */
    static final String ENABLED = "stockflow.assistant.receipt-drafts.enabled";

    /** The resource of {@code POST /api/v1/goods-receipts} ({@code PurchaseOrderResources.GOODS_RECEIPTS}). */
    static final String GOODS_RECEIPTS = "procurement-goods-receipts";

    private final ReceiptDraftService drafts;

    ReceiptDraftController(ReceiptDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a delivery-note photo into a draft goods receipt for a purchase order",
            description = "Needs procurement-goods-receipts:CREATE, as creating the receipt does; a warehouse-limited "
                    + "user only for orders of their warehouses. Multipart: file (JPEG, PNG or WEBP, judged by its "
                    + "bytes, at most 5 MB) and poNumber. Nothing is stored or written: the photo goes to the "
                    + "assistant's model provider, and the answer is a proposal to check and enter on the "
                    + "goods-receipt screen. Shares the assistant's rate limit of 10 a minute; does not count against "
                    + "the daily question cap. 404 PURCHASE_ORDER_NOT_FOUND, 409 PURCHASE_ORDER_NOT_RECEIVABLE, 413, "
                    + "415; 429 ASSISTANT_QUOTA_EXHAUSTED; 503 ASSISTANT_UNAVAILABLE (retry), ASSISTANT_MISCONFIGURED "
                    + "or ASSISTANT_DISABLED (do not).")
    @RequiresPermission(resource = GOODS_RECEIPTS, action = Action.CREATE)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<ReceiptDraftResponse> draft(@AuthenticatedUser CurrentUser user,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam("poNumber") String poNumber) {
        ScopeGuards.requireBackOffice(user);
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    content);
            return ApiResponse.ok(toResponse(drafts.draft(poNumber, upload)));
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the multipart upload", unreadable);
        }
    }

    static ReceiptDraftResponse toResponse(ReceiptDraft draft) {
        return new ReceiptDraftResponse(draft.poNumber(), draft.supplierName(),
                draft.lines().stream().map(line -> new ReceiptDraftResponse.Line(line.poLineNo(), line.sku(),
                        line.ordered(), line.outstanding(), line.proposedQuantity(), line.lotNumber(),
                        line.expiryDate(), line.readText(), line.warnings())).toList(),
                draft.unmatched().stream().map(unmatched -> new ReceiptDraftResponse.Unmatched(unmatched.readText(),
                        unmatched.quantity())).toList(),
                draft.warnings());
    }
}
