package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.CartonInvestigation;
import com.stockflow.assistant.internal.service.ChatDrafts;
import com.stockflow.assistant.internal.service.CollectionList;
import com.stockflow.assistant.internal.service.Draft;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.MatchSuggestions;
import com.stockflow.assistant.internal.service.OrderNextSteps;
import com.stockflow.assistant.internal.service.ProductionFollowUps;
import com.stockflow.assistant.internal.service.QuotePriceReview;
import com.stockflow.assistant.internal.service.ReminderDrafts;
import com.stockflow.assistant.internal.service.WorkQueue;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding 6 of the 27/11 review: a feature's use is counted by its endpoint after the feature answered
 * ({@link FeatureUse#record}'s rule), under its {@link FeatureUse} key - never when it was refused or no model answered,
 * or a day of 503s would read as a day of use.
 */
class AssistantFeatureCountingTest {

    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_MANAGER),
            Set.of(), DataScope.ALL, Set.of());
    private static final Draft DRAFT = new Draft("text", List.of(), List.of(), "model", Instant.EPOCH);

    private final AssistantTelemetry telemetry = mock(AssistantTelemetry.class);
    private final OrderNextSteps nextSteps = mock(OrderNextSteps.class);
    private final ChatDrafts drafts = mock(ChatDrafts.class);
    private final QuotePriceReview priceReview = mock(QuotePriceReview.class);
    private final ReminderDrafts reminders = mock(ReminderDrafts.class);
    private final ProductionFollowUps followUps = mock(ProductionFollowUps.class);
    private final AssistantSalesController sales = new AssistantSalesController(nextSteps, drafts, priceReview,
            telemetry);
    private final AssistantMoneyController money = new AssistantMoneyController(mock(MatchSuggestions.class),
            mock(CollectionList.class), reminders, telemetry);
    private final AssistantOperationsController operations = new AssistantOperationsController(mock(WorkQueue.class),
            mock(CartonInvestigation.class), followUps, telemetry);

    @Test
    @DisplayName("each answered feature is counted once, under its own key")
    void countedOnceAnswered() {
        UUID id = UUID.randomUUID();
        when(nextSteps.of("SO-1", USER)).thenReturn(new OrderNextSteps.NextSteps(id, "SO-1", "CONFIRMED", "x",
                List.of(), List.of(), true, false));
        when(drafts.reply(id, null, USER)).thenReturn(new ChatDrafts.ReplyDraft(DRAFT, List.of(), "vi"));
        when(drafts.summary(id, USER)).thenReturn(new ChatDrafts.Summary(DRAFT, List.of(), List.of(), List.of(),
                List.of()));
        when(priceReview.of(id, USER)).thenReturn(new QuotePriceReview.Review(id, "QT-1", "DRAFT", null, null, null,
                List.of(), null, List.of()));
        when(reminders.draft(id, ReminderDrafts.Tone.GENTLE)).thenReturn(DRAFT);
        when(followUps.draft(id)).thenReturn(DRAFT);

        sales.nextSteps("SO-1", USER);
        sales.replyDraft(id, null, USER);
        sales.summary(id, USER);
        sales.priceReview(id, USER);
        money.reminderDraft(id, null, USER);
        operations.producerMessageDraft(USER, id);

        for (String key : List.of(FeatureUse.NEXT_STEPS, FeatureUse.REPLY_DRAFT, FeatureUse.CHAT_SUMMARY,
                FeatureUse.PRICE_REVIEW, FeatureUse.REMINDER_DRAFT, FeatureUse.PRODUCER_MESSAGE_DRAFT)) {
            verify(telemetry, times(1)).featureUsed(key, USER);
        }
    }

    @Test
    @DisplayName("a refusal or a model that did not answer is not a use")
    void notCountedWhenNotAnswered() {
        UUID id = UUID.randomUUID();
        BusinessException unavailable = new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "no model");
        when(nextSteps.of(anyString(), any())).thenThrow(new NotFoundException("Order", "SO-1"));
        when(drafts.reply(any(), any(), any())).thenThrow(unavailable);
        when(drafts.summary(any(), any())).thenThrow(unavailable);
        when(priceReview.of(any(), any())).thenThrow(new NotFoundException("Quote", id));
        when(reminders.draft(any(), any())).thenThrow(new BusinessException(ErrorCode.INSTALLMENT_NOT_DUE_SOON, "x"));
        when(followUps.draft(any())).thenThrow(unavailable);

        for (Executable call : List.<Executable>of(() -> sales.nextSteps("SO-1", USER),
                () -> sales.replyDraft(id, null, USER), () -> sales.summary(id, USER),
                () -> sales.priceReview(id, USER), () -> money.reminderDraft(id, null, USER),
                () -> operations.producerMessageDraft(USER, id))) {
            assertThatThrownBy(call::execute).isInstanceOf(RuntimeException.class);
        }
        verify(telemetry, never()).featureUsed(anyString(), any());
        verify(telemetry, never()).featureUsed(eq(FeatureUse.NEXT_STEPS), any());
    }
}
