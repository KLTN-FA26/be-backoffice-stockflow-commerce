package com.stockflow.assistant.internal.service;

import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantStreamsTest {

    @AfterEach
    void clear() {
        DataScopeContext.clear();
    }

    @Test
    @DisplayName("a streamed answer runs in the scope of the request that started it: with security off, the system "
            + "scope the request filter installed, without which every tool on the worker would be refused")
    void streamsInTheRequestsScope() throws Exception {
        AssistantService assistant = mock(AssistantService.class);
        AtomicReference<String> scope = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        when(assistant.askAsOwner(any(), any(), any(), any())).thenAnswer(call -> {
            scope.set(DataScopeContext.current().map(current -> current.user().username()).orElse("none"));
            asked.countDown();
            return null;
        });
        AssistantProperties properties = new AssistantProperties(true, "key", "claude-opus-5-5", "low", 6, 16000, 50,
                90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "anthropic");
        CurrentUser system = new CurrentUser(UUID.randomUUID(), "local-dev", Set.of(), Set.of(), DataScope.ALL,
                Set.of());
        DataScopeContext.setSystemScope(system);

        new AssistantStreams(assistant, properties, new Messages(new StaticMessageSource()))
                .ask(UUID.randomUUID(), "Chào", system, answer -> "ok");

        assertThat(asked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(scope.get()).isEqualTo("local-dev");
    }
}
