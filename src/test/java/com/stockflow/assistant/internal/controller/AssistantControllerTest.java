package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AskRequest;
import com.stockflow.assistant.internal.service.AssistantService;
import com.stockflow.assistant.internal.service.AssistantStreams;
import com.stockflow.assistant.internal.service.ConversationService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The two ways of asking, side by side: one rate-limit budget between them (SEC-5), and an {@code Idempotency-Key}
 * that only the plain one can honour (LOOP-6). The full stack is in {@code AssistantRetryIntegrationTest}.
 */
class AssistantControllerTest {

    private static final CurrentUser PLANNER = new CurrentUser(UUID.randomUUID(), "planner",
            Set.of(Role.INVENTORY_PLANNER), Set.of(), DataScope.ALL, Set.of());

    @Test
    @DisplayName("both ways of asking draw on one named budget: alternating them must not double the limit")
    void oneBudgetForBothWaysOfAsking() {
        RateLimit plain = limitOf("ask");
        RateLimit streamed = limitOf("askStreaming");

        // Without a name each method is its own bucket (RateLimitInterceptor): the real limit would be twice the one
        // meant.
        assertThat(plain.bucket()).isNotBlank().isEqualTo(streamed.bucket());
        assertThat(List.of(plain.limit(), plain.perSeconds(), plain.key()))
                .isEqualTo(List.of(streamed.limit(), streamed.perSeconds(), streamed.key()))
                .isEqualTo(List.of(10, 60, RateLimit.Key.USER));
    }

    @Test
    @DisplayName("the stream refuses an Idempotency-Key with 400 before it checks or asks anything")
    void theStreamRefusesAKey() {
        AssistantService assistant = mock(AssistantService.class);
        AssistantStreams streams = mock(AssistantStreams.class);
        AssistantController controller = new AssistantController(mock(ConversationService.class), assistant, streams,
                mock(AssistantWebMapper.class));

        assertThatThrownBy(() -> controller.askStreaming(PLANNER, UUID.randomUUID(), "retry-key-0001",
                new AskRequest("Xin chào")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errorCode().httpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("Idempotency-Key");
                });
        verifyNoInteractions(assistant, streams);
    }

    private static RateLimit limitOf(String method) {
        return Arrays.stream(AssistantController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(method))
                .map(candidate -> candidate.getAnnotation(RateLimit.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + method));
    }
}
