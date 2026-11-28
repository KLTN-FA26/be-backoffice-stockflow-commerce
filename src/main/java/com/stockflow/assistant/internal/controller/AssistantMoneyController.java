package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.CollectionsResponse;
import com.stockflow.assistant.internal.controller.dto.DraftResponse;
import com.stockflow.assistant.internal.controller.dto.MatchSuggestionsResponse;
import com.stockflow.assistant.internal.controller.dto.ReminderDraftRequest;
import com.stockflow.assistant.internal.controller.dto.ReminderDraftResponse;
import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.CollectionList;
import com.stockflow.assistant.internal.service.Draft;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.MatchSuggestions;
import com.stockflow.assistant.internal.service.ReminderDrafts;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.api.PayableInstallment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * The assistant's help with money, inside the screens the Accountant and Sales already work in
 * ({@code docs/assistant-work-features-fe-contract.md} §2): where to look for a bank transfer the feed could not match,
 * which installments to chase first, and a reminder to send for one. Read-only: the Accountant assigns the transfer and
 * the reminder is sent by whoever edits it, each through their own screen (ADR-0015).
 *
 * <p><b>The screen's permission, the screen's scope.</b> The suggestions are guarded as the bank transfer's review
 * screen ({@code payment-bank-transactions:READ}), the list and the reminder as the installment list
 * ({@code payment-installments:READ}). Both lists are every customer's, as those screens are, so a portal customer -
 * who holds {@code payment-installments:READ} for its own installments - is refused first ({@link ScopeGuards}). Money
 * is not limited by warehouse: those screens are not either.</p>
 *
 * <p>The list and the suggestions ask no model and have their own generous bucket; the reminder spends the
 * questions' ({@link AssistantResources#QUESTIONS_BUCKET}).</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantMoneyController {

    private static final String BANK_TRANSACTIONS = "payment-bank-transactions";
    private static final String INSTALLMENTS = "payment-installments";

    private final MatchSuggestions suggestions;
    private final CollectionList collections;
    private final ReminderDrafts reminders;
    private final AssistantTelemetry telemetry;

    AssistantMoneyController(MatchSuggestions suggestions, CollectionList collections, ReminderDrafts reminders,
                             AssistantTelemetry telemetry) {
        this.suggestions = suggestions;
        this.collections = collections;
        this.reminders = reminders;
        this.telemetry = telemetry;
    }

    @GetMapping("/bank-transactions/{transactionId}/match-suggestions")
    @Operation(summary = "The installments a bank transfer waiting for review most likely pays",
            description = "Needs payment-bank-transactions:READ, as the transfer's review screen. At most three, best "
                    + "first, each scored 0-100 with the signals that scored it; empty when nothing reaches the floor, "
                    + "and always empty for a transfer that is not NEEDS_REVIEW. ambiguous is true when the best two "
                    + "score the same: the order is then no preference. Deterministic: no model is asked. It only "
                    + "points: the transfer is still assigned on its own screen. 404 BANK_TRANSACTION_NOT_FOUND.")
    @RequiresPermission(resource = BANK_TRANSACTIONS, action = Action.READ, scope = DataScope.ALL)
    @RateLimit(limit = AssistantResources.WORK_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.WORK_BUCKET)
    public ApiResponse<MatchSuggestionsResponse> matchSuggestions(@PathVariable UUID transactionId,
                                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        MatchSuggestions.Result result = suggestions.suggest(transactionId);
        telemetry.featureUsed(FeatureUse.MATCH_SUGGESTIONS, user);
        BankTransferReview.Transfer t = result.transfer();
        return ApiResponse.ok(new MatchSuggestionsResponse(
                new MatchSuggestionsResponse.TransactionResponse(t.id(), t.status(), t.amount(), t.transactionAt(),
                        t.content(), t.reviewReason()),
                result.suggestions().stream().map(s -> {
                    PayableInstallment i = s.installment();
                    return new MatchSuggestionsResponse.SuggestionResponse(i.id(), i.contractNumber(), i.orderNumber(),
                            s.customerName(), i.name(), i.transferReference(), i.outstanding(), i.dueDate(),
                            s.score(), s.reasons(), installmentHref(i.id()));
                }).toList(), result.ambiguous()));
    }

    @GetMapping("/collections")
    @Operation(summary = "Installments overdue or due within the week, the ones to chase first on top",
            description = "Needs payment-installments:READ, as the installment list; back office only. Ranked by "
                    + "days overdue, what is still owed, the customer's other overdue money and its credit limit, each "
                    + "with why and the contract's salesperson. At most 50 items; total and truncated say how many "
                    + "there are. Deterministic: no model is asked.")
    @RequiresPermission(resource = INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    @RateLimit(limit = AssistantResources.WORK_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.WORK_BUCKET)
    public ApiResponse<CollectionsResponse> collections(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        CollectionList.Result result = collections.list();
        telemetry.featureUsed(FeatureUse.COLLECTIONS, user);
        return ApiResponse.ok(new CollectionsResponse(result.items().stream().map(item -> {
            PayableInstallment i = item.installment();
            return new CollectionsResponse.ItemResponse(i.id(), i.contractNumber(), i.orderNumber(),
                    new CollectionsResponse.PartyResponse(i.customerId(), item.customerName()),
                    item.salespersonId() == null ? null
                            : new CollectionsResponse.PartyResponse(item.salespersonId(), item.salespersonName()),
                    i.name(), i.outstanding(), i.dueDate(), i.daysOverdue(), item.priority(), item.why(),
                    installmentHref(i.id()));
        }).toList(), result.total(), result.truncated()));
    }

    @PostMapping("/collections/{installmentId}/reminder-draft")
    @Operation(summary = "A payment reminder to the customer for one installment, to edit and send",
            description = "Needs payment-installments:READ; back office only. Model-backed: a polite Vietnamese "
                    + "message naming the contract, the installment, what it still owes, its due date and the "
                    + "transfer content to use, nothing else; the customer is pseudonymised to the model. Codes and "
                    + "figures no fact holds are listed in unverifiedCodes. Nothing is sent. 404 INSTALLMENT_NOT_FOUND; "
                    + "409 INSTALLMENT_NOT_PAYABLE for one paid or cancelled; 409 INSTALLMENT_NOT_DUE_SOON for one "
                    + "with no due date yet or due more than 7 days ahead (the collections list's window); 400 "
                    + "VALIDATION_FAILED for another tone; "
                    + "503 ASSISTANT_UNAVAILABLE when no model writes it; 429 RATE_LIMITED, shared with the "
                    + "assistant's questions.")
    @RequiresPermission(resource = INSTALLMENTS, action = Action.READ, scope = DataScope.ALL)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<ReminderDraftResponse> reminderDraft(@PathVariable UUID installmentId,
                                                            @RequestBody(required = false) ReminderDraftRequest body,
                                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Draft draft = reminders.draft(installmentId, tone(body));
        telemetry.featureUsed(FeatureUse.REMINDER_DRAFT, user);
        return ApiResponse.ok(new ReminderDraftResponse(DraftResponse.of(draft)));
    }

    private static ReminderDrafts.Tone tone(ReminderDraftRequest body) {
        if (body == null || body.tone() == null || body.tone().isBlank()) {
            return ReminderDrafts.Tone.GENTLE;
        }
        try {
            return ReminderDrafts.Tone.valueOf(body.tone().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "tone is GENTLE or FIRM");
        }
    }

    private static String installmentHref(UUID installmentId) {
        return "/admin/payment/installments/" + installmentId;
    }
}
