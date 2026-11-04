package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ConversationOrigin;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.WatchRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RunAs;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AssistantWatch}'s decisions, every collaborator stubbed: what is posted, answered, waited on or set aside. */
class AssistantWatchTest {

    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    private static final UUID USER = UUID.fromString("01a1a000-0000-7000-8000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("01a1a000-0000-7000-8000-0000000000aa");
    private static final String LATE = AssistantWatch.OVERDUE_PURCHASE_ORDERS;
    private static final String LOW = AssistantWatch.LOW_STOCK;
    private static final CurrentUser MANAGER = new CurrentUser(USER, "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(PermissionCode.of(ToolPermissions.ASSISTANT, Action.CREATE)), DataScope.WAREHOUSE, Set.of("W"),
            Set.of(WAREHOUSE));
    private static final String ONE_LATE = "{\"purchaseOrders\":[{\"poNumber\":\"PO-1\",\"overdue\":true,"
            + "\"expectedAt\":\"2026-11-01\"}],\"total\":1,\"truncated\":false}";

    private final RunAs runAs = mock(RunAs.class);
    private final AssistantTools tools = mock(AssistantTools.class);
    private final WatchRepository watched = mock(WatchRepository.class);
    private final Inbox inbox = mock(Inbox.class);
    private final AssistantService assistant = mock(AssistantService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final ToolAvailability availability = mock(ToolAvailability.class);
    private final AssistantProperties assistantProperties = mock(AssistantProperties.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final List<WatchRepository.SeenItem> pending = new ArrayList<>();
    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubs() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("manager", null,
                "WAREHOUSE_MANAGER", ToolPermissions.ASSISTANT + ":CREATE"));
        when(runAs.call(eq(USER), eq(AssistantWatch.ACTOR), any()))
                .thenAnswer(call -> ((Function<CurrentUser, Object>) call.getArgument(2)).apply(MANAGER));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(availability.grantedTo(MANAGER)).thenReturn(List.of("findPurchaseOrders", "findGoodsReceipts",
                "findLowStock", "proposeReorder"));
        when(assistantProperties.answerLease()).thenReturn(Duration.ofMinutes(15));
        when(watched.state(eq(USER), any())).thenReturn(Optional.of(new WatchRepository.State(
                AssistantWatch.view(MANAGER), NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofMinutes(15)), null)));
        when(watched.pending(USER)).thenAnswer(call -> List.copyOf(pending));
        when(inbox.post(any(), any())).thenReturn(1);
        when(assistant.answersInTheBackground()).thenReturn(true);
        when(conversations.startProactive(USER)).thenReturn(new ConversationService.ConversationView(conversation,
                null, 0, NOW, NOW, ConversationOrigin.PROACTIVE));
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
    }

    private AssistantWatch watch(List<String> post, List<String> analyse) {
        return new AssistantWatch(runAs, tools, mock(ToolRunRecorder.class), watched, inbox, assistant, conversations,
                availability, new AssistantWatchProperties(true, post, analyse, null, 3, 5,
                        Duration.ofMinutes(30), Duration.ofDays(4), List.of()),
                assistantProperties, new Messages(new StaticMessageSource()), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private void lists(String answer) {
        when(tools.execute(any(ToolCall.class), eq(ToolChannel.PROACTIVE))).thenAnswer(call ->
                new ToolExecution(call.getArgument(0), ToolOutcome.OK, answer, 3));
    }

    private static AssistantWatch.Budget budget() {
        return new AssistantWatch.Budget(5, NOW.plus(Duration.ofHours(1)));
    }

    private WatchRepository.SeenItem waiting(String section, String key) {
        WatchRepository.SeenItem item = new WatchRepository.SeenItem(UUID.randomUUID(), section, key, NOW, false);
        pending.add(item);
        return item;
    }

    private InboxMessage posted() {
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(USER)), message.capture());
        return message.getValue();
    }

    private static AssistantService.Answer answer(boolean proposed) {
        AssistantService.Answer answer = mock(AssistantService.Answer.class);
        when(answer.proposals()).thenReturn(proposed ? List.of(new AnswerSources.Proposal("PURCHASE_ORDER_DRAFT",
                "SUP", "Sup", NOW, List.of())) : List.of());
        return answer;
    }

    @Test
    @DisplayName("a question refused before anything was stored leaves its items not asked about: they are posted as "
            + "news, and the run's answer is given back")
    void aRefusalBeforeTheClaim() {
        lists(ONE_LATE);
        WatchRepository.SeenItem item = waiting(LATE, "PO-1");
        when(assistant.askProactively(eq(conversation), any(), eq(MANAGER), any()))
                .thenThrow(new BusinessException(ErrorCode.ASSISTANT_DISABLED));
        when(conversations.discardEmptyProactive(conversation, USER)).thenReturn(true);
        AssistantWatch.Budget budget = new AssistantWatch.Budget(1, NOW.plus(Duration.ofHours(1)));

        AssistantWatch.Look look = watch(List.of(LATE), List.of(LATE)).look(USER, budget);

        assertThat(look.answered()).isZero();
        verify(assistant).askProactively(eq(conversation), any(), eq(MANAGER),
                eq(Set.of("findPurchaseOrders", "findGoodsReceipts")));
        verify(watched).analysed(List.of(item.id()), conversation, NOW);
        verify(watched).notAnalysed(List.of(item.id()));
        assertThat(posted().category()).isEqualTo(AssistantWatch.NEWS);
        assertThat(budget.take(NOW, Duration.ofMinutes(15))).as("the answer was given back").isTrue();
    }

    @Test
    @DisplayName("a list longer than one read changes nothing it cannot see, and says so once")
    void aListReadInPart() {
        lists("{\"purchaseOrders\":[{\"poNumber\":\"PO-9\",\"overdue\":true,\"expectedAt\":\"2026-10-01\"}],"
                + "\"total\":40,\"truncated\":true}");
        WatchRepository.SeenItem item = new WatchRepository.SeenItem(UUID.randomUUID(), LATE, "PO-9", NOW, false);
        when(watched.see(eq(USER), eq(LATE), anyCollection(), any())).thenReturn(List.of(item));

        AssistantWatch.Look look = watch(List.of(LATE), List.of()).look(USER, budget());

        assertThat(look.newItems()).isZero();
        verify(watched, never()).forgetUnseen(any(), any(), any());
        verify(watched).looked(USER, LATE, NOW, NOW);
        verify(watched).dealtWith(List.of(item.id()), NOW);
        assertThat(posted().dedupeKey()).isEqualTo("assistant:partial:" + LATE + ":" + NOW.toEpochMilli());
    }

    @Test
    @DisplayName("a long list of codes is cut to fit a message, the rest counted")
    void aLongBody() {
        lists("{\"purchaseOrders\":[],\"total\":0,\"truncated\":false}");
        for (int i = 0; i < 60; i++) {
            waiting(LATE, "PO-HCM-2026-11-05-%04d-LONG-CODE".formatted(i));
        }

        watch(List.of(LATE), List.of()).look(USER, budget());

        assertThat(posted().body()).hasSizeLessThanOrEqualTo(InboxMessage.BODY_MAX)
                .startsWith("PO-HCM-2026-11-05-0000-LONG-CODE").contains("+");
    }

    @Test
    @DisplayName("an answer that failed after its question was stored, on a list the watch only answers about, is told "
            + "as such and points to its conversation")
    void aFailedAnswerIsTold() {
        lists("{\"skus\":[{\"sku\":\"CUP-12OZ\"}],\"total\":1,\"truncated\":false}");
        WatchRepository.SeenItem item = waiting(LOW, "CUP-12OZ");
        when(assistant.askProactively(eq(conversation), any(), eq(MANAGER), any()))
                .thenThrow(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE));
        when(conversations.discardEmptyProactive(conversation, USER)).thenReturn(false);

        watch(List.of(), List.of(LOW)).look(USER, budget());

        InboxMessage message = posted();
        assertThat(message.category()).isEqualTo(AssistantWatch.ANSWER);
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.sourceId()).isEqualTo(conversation.toString());
        verify(watched, never()).notAnalysed(any());
        verify(watched).dealtWith(List.of(item.id()), NOW);
    }

    @Test
    @DisplayName("an answer about low stock that proposed nothing does not say it prepared a proposal; at most thirty "
            + "items go into one question, the rest wait")
    void whatAnAnswerSays() {
        lists("{\"skus\":[],\"total\":0,\"truncated\":false}");
        for (int i = 0; i < 35; i++) {
            waiting(LOW, "SKU-%02d".formatted(i));
        }
        AssistantService.Answer none = answer(false);
        when(assistant.askProactively(eq(conversation), any(), eq(MANAGER), any())).thenReturn(none);

        AssistantWatch.Look look = watch(List.of(), List.of(LOW)).look(USER, budget());

        assertThat(look.answered()).isOne();
        assertThat(posted().title()).as("the fallback of assistant.watch.answered.lowStock.none").contains("looked at 30");
        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(assistant).askProactively(eq(conversation), question.capture(), eq(MANAGER),
                eq(Set.of("findLowStock", "proposeReorder")));
        assertThat(question.getValue()).contains("SKU-29").doesNotContain("SKU-30");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> dealt = ArgumentCaptor.forClass(List.class);
        verify(watched).dealtWith(dealt.capture(), eq(NOW));
        assertThat(dealt.getValue()).as("the five not asked about wait for the next look").hasSize(30);
    }
}
