package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.web.CorrelationIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers a question as a stream of server-sent events (design §5.2): one {@code tool} event as each tool finishes,
 * then {@code answer}, or {@code error}.
 *
 * <p><b>The thread rule.</b> The loop must leave the request thread here, and everything the tools rely on is
 * thread-bound: the {@code SecurityContext} (the permission aspect reads it), the data scope (the aspect writes it),
 * the locale (the error and closing messages) and the MDC (the correlation id in the logs). The executor copies the
 * {@code SecurityContext}; this class copies the locale and the MDC; the data scope is not copied but re-established —
 * {@link AssistantService#askAsOwner} carries {@code @RequiresPermission}, so the aspect checks the caller again and
 * installs the scope on the worker thread itself, exactly as it did on the request thread.</p>
 *
 * <p>Virtual threads: an answer spends seconds waiting on the provider, and a virtual thread costs nothing to block.</p>
 */
@Service
public class AssistantStreams {

    private static final Logger log = LoggerFactory.getLogger(AssistantStreams.class);

    private final AssistantService assistant;
    private final AssistantProperties properties;
    private final Messages messages;
    private final ExecutorService executor =
            new DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor());

    AssistantStreams(AssistantService assistant, AssistantProperties properties, Messages messages) {
        this.assistant = assistant;
        this.properties = properties;
        this.messages = messages;
    }

    /**
     * Starts answering and returns at once; the emitter receives the events and is completed by the worker.
     *
     * @param toResponse turns the finished answer into the response body the {@code answer} event carries
     */
    public SseEmitter ask(UUID conversationId, String text, CurrentUser user,
                          java.util.function.Function<AssistantService.Answer, Object> toResponse) {
        SseEmitter emitter = new SseEmitter(properties.answerLease().toMillis()) {
            // Any nginx on the way must pass each event on as it is written; deploy/nginx turns buffering off too.
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                super.extendResponse(outputMessage);
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
            }
        };
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            LocaleContextHolder.setLocale(locale);
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                AssistantService.Answer answer = assistant.askAsOwner(conversationId, text, user,
                        call -> send(emitter, "tool", call));
                send(emitter, "answer", toResponse.apply(answer));
                emitter.complete();
            } catch (BusinessException refused) {
                send(emitter, "error", new StreamError(refused.errorCode().name(),
                        messages.forCode(refused.errorCode())));
                emitter.complete();
            } catch (RuntimeException failed) {
                log.error("A streamed assistant answer failed (correlation id {})",
                        MDC.get(CorrelationIdFilter.MDC_KEY), failed);
                send(emitter, "error", new StreamError(ErrorCode.INTERNAL_ERROR.name(),
                        messages.forCode(ErrorCode.INTERNAL_ERROR)));
                emitter.complete();
            } finally {
                LocaleContextHolder.resetLocaleContext();
                MDC.clear();
            }
        });
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** The body of an {@code error} event: the stable code and the translated message, as in the API envelope. */
    public record StreamError(String errorCode, String message) {
    }

    /** A client that went away is not an error worth more than a debug line: the answer is stored regardless. */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException gone) {
            log.debug("The client of a streamed answer went away before the '{}' event", event);
        }
    }
}
