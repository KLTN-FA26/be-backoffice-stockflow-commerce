package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.CollectionList;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.MatchSuggestions;
import com.stockflow.assistant.internal.service.ReminderDrafts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.payment.api.BankTransferReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The money work features count a use only once the feature has answered (contract §5.2, {@code FeatureUse.record}): a
 * refused or failed call is no use, and a day of failures must not read as a day of use.
 */
class AssistantMoneyControllerTest {

    private static final CurrentUser ACCOUNTANT = new CurrentUser(UUID.randomUUID(), "accountant",
            Set.of(Role.ACCOUNTANT), Set.of(), DataScope.ALL, Set.of());

    private final MatchSuggestions suggestions = mock(MatchSuggestions.class);
    private final CollectionList collections = mock(CollectionList.class);
    private final AssistantTelemetry telemetry = mock(AssistantTelemetry.class);
    private final AssistantMoneyController controller = new AssistantMoneyController(suggestions, collections,
            mock(ReminderDrafts.class), telemetry);

    @Test
    @DisplayName("match suggestions: counted under its key after the answer, with the ambiguity flag passed on")
    void matchSuggestionsCountedOnceAnswered() {
        UUID id = UUID.randomUUID();
        when(suggestions.suggest(id)).thenReturn(new MatchSuggestions.Result(new BankTransferReview.Transfer(id,
                "NEEDS_REVIEW", BigDecimal.TEN, Instant.now(), "CK", "NO_INSTALLMENT_NAMED", null), List.of(), true));

        var response = controller.matchSuggestions(id, ACCOUNTANT);

        assertThat(response.data().ambiguous()).isTrue();
        verify(telemetry).featureUsed(FeatureUse.MATCH_SUGGESTIONS, ACCOUNTANT);
    }

    @Test
    @DisplayName("match suggestions refused (unknown transfer): not a use")
    void refusedIsNoUse() {
        UUID id = UUID.randomUUID();
        when(suggestions.suggest(id)).thenThrow(new BusinessException(ErrorCode.BANK_TRANSACTION_NOT_FOUND, "none"));

        assertThatThrownBy(() -> controller.matchSuggestions(id, ACCOUNTANT)).isInstanceOf(BusinessException.class);
        verify(telemetry, never()).featureUsed(anyString(), any());
    }

    @Test
    @DisplayName("collections: counted under its key after the answer; a failed read is not a use")
    void collectionsCountedOnceAnswered() {
        when(collections.list()).thenReturn(new CollectionList.Result(LocalDate.of(2026, 11, 27), List.of(), 0,
                false)).thenThrow(new IllegalStateException("database gone"));

        controller.collections(ACCOUNTANT);
        assertThatThrownBy(() -> controller.collections(ACCOUNTANT)).isInstanceOf(IllegalStateException.class);

        verify(telemetry).featureUsed(FeatureUse.COLLECTIONS, ACCOUNTANT);
    }
}
