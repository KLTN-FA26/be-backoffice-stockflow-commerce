package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.chat.api.ChatContextKind;
import com.stockflow.chat.api.ChatContextRef;
import com.stockflow.chat.api.ChatTranscript;
import com.stockflow.chat.api.ChatTranscripts;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.CustomerDocument;
import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.order.api.DeliverySchedules;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.payment.api.CustomerInstallments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The staff chat's reply draft and summary (contract §1.2, §1.3): what the model is given and what it is not, the
 * customer's name kept from it and put back, the grounding check, and a model that does not answer.
 */
class ChatDraftsTest {

    private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID OTHER_CUSTOMER = UUID.randomUUID();
    private static final String NAME = "Công ty Ly Giấy Sài Gòn";
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID FOREIGN_ORDER = UUID.randomUUID();
    private static final UUID DRAFT_ORDER = UUID.randomUUID();
    private static final String NUMBER = "SO-20261120-000101";
    private static final String NOTE = "Ghi chu noi bo: khach no qua han, giu don";
    private static final UUID PORTAL_USER = UUID.randomUUID();
    private static final String CONTACT = "Nguyễn Thị Lan Anh";
    private static final UUID QUOTE = UUID.randomUUID();

    private final ChatTranscripts chats = mock(ChatTranscripts.class);
    private final OrderService orders = mock(OrderService.class);
    private final CustomerDocuments documents = mock(CustomerDocuments.class);
    private final QuoteCards quotes = mock(QuoteCards.class);
    private final CustomerInstallments installments = mock(CustomerInstallments.class);
    private final DeliverySchedules schedules = mock(DeliverySchedules.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CustomerLegalProfiles legalProfiles = mock(CustomerLegalProfiles.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final LanguageModel second = mock(LanguageModel.class);
    private final ChatDrafts drafts = drafts(model);

    private ChatDrafts drafts(LanguageModel... models) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        List<ModelRouter.Candidate> candidates = new java.util.ArrayList<>();
        for (int n = 0; n < models.length; n++) {
            candidates.add(new ModelRouter.Candidate(n == 0 ? "scripted" : "scripted-" + n, models[n]));
        }
        DraftWriter writer = new DraftWriter(new ModelRouter(candidates,
                new ModelRoutingProperties(List.of(), true, null), ZoneOffset.UTC, clock), customers, legalProfiles,
                identities, clock);
        return new ChatDrafts(chats, orders, documents, quotes, installments, schedules, writer,
                new Messages(new StaticMessageSource()), new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void setUp() {
        when(model.available()).thenReturn(true);
        when(second.available()).thenReturn(true);
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(new CustomerSummary(CUSTOMER, PORTAL_USER, NAME,
                "mua@lygiay.vn", "0903123456", "0312345678", null, "ACTIVE", 0, NOW)));
        when(identities.displayNames(any())).thenReturn(Map.of(PORTAL_USER, CONTACT));
        when(documents.order(ORDER)).thenReturn(Optional.of(new CustomerDocument(ORDER, CUSTOMER, NUMBER,
                "CONFIRMED", null, new BigDecimal("12000000"), "VND", NOW)));
        when(documents.order(FOREIGN_ORDER)).thenReturn(Optional.of(new CustomerDocument(FOREIGN_ORDER,
                OTHER_CUSTOMER, "SO-20261120-000999", "CONFIRMED", null, new BigDecimal("99000000"), "VND", NOW)));
        when(documents.order(DRAFT_ORDER)).thenReturn(Optional.of(new CustomerDocument(DRAFT_ORDER, CUSTOMER,
                "SO-20261120-000555", "DRAFT", null, new BigDecimal("55000000"), "VND", NOW)));
        when(installments.openOf(eq(CUSTOMER), anyInt())).thenReturn(List.of(new CustomerInstallments.OpenInstallment(
                UUID.randomUUID(), UUID.randomUUID(), "HD-2026-0101", ORDER, NUMBER, 2, "Đợt 2",
                new BigDecimal("6000000"), BigDecimal.ZERO, "VND", LocalDate.parse("2026-12-05"), "DUE", false,
                BigDecimal.ZERO, new BigDecimal("6000000"))));
        when(schedules.customerSchedule(CUSTOMER, ORDER)).thenReturn(List.of(new DeliverySchedules.Batch(1,
                LocalDate.parse("2026-12-10"), LocalDate.parse("2026-12-12"), "AWAITING_STOCK", false)));
        when(orders.findById(ORDER)).thenReturn(Optional.of(new OrderSummary(ORDER, NUMBER, CUSTOMER,
                OrderStatus.CONFIRMED, Money.vnd(12_000_000), List.of(), NOW, "sales", null, null, null, null, null,
                null, null, UUID.randomUUID(), NOW, null, null)));
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(), 0, 5, 0));
    }

    @Test
    @DisplayName("a reply reads the conversation as the customer saw it, and the customer's own documents only: "
            + "another customer's order and a draft order never reach the model, nor the customer's name")
    void replyIsBuiltFromWhatTheCustomerMayKnow() {
        transcript(false, List.of(ORDER, FOREIGN_ORDER, DRAFT_ORDER),
                message(1, "CUSTOMER", "Chào em, " + NAME + " hỏi đơn " + NUMBER + " khi nào giao?"));
        reply("Dạ KH#1, đơn " + NUMBER + " dự kiến giao từ 10/12/2026; đợt 2 là 6.000.000 VND, hạn 05/12/2026. "
                + "Mã vận đơn AHM-777123.");

        ChatDrafts.ReplyDraft draft = drafts.reply(CONVERSATION, "nhắc khách đợt 2", sales());

        verify(chats).recent(CONVERSATION, ChatDrafts.REPLY_MESSAGES, false, sales());
        String sent = sentToModel();
        assertThat(sent).contains(NUMBER, "HD-2026-0101", "10/12/2026", "KH#1", "nhắc khách đợt 2")
                .doesNotContain(NAME, "SO-20261120-000999", "99000000", "SO-20261120-000555", NOTE);
        assertThat(draft.draft().text()).startsWith("Dạ " + NAME + ",").doesNotContain("KH#1");
        assertThat(draft.draft().unverifiedCodes()).containsExactly("AHM-777123");
        assertThat(draft.orderNumbers()).containsExactly(NUMBER);
        assertThat(draft.language()).isEqualTo("vi");
        assertThat(draft.draft().sources()).extracting(Draft.Source::kind)
                .containsExactly(Draft.Kind.ORDER, Draft.Kind.INSTALLMENT, Draft.Kind.CHAT);
        assertThat(draft.draft().sources().getFirst().href()).isEqualTo("/admin/orders/" + ORDER);
        assertThat(draft.draft().model()).isEqualTo("scripted");
    }

    @Test
    @DisplayName("without the order screen the draft knows no shipments, and order numbers typed are not looked up")
    void orderDetailsNeedTheOrderScreen() {
        transcript(false, List.of(ORDER), message(1, "CUSTOMER", "Đơn SO-20261120-000777 thì sao?"));
        reply("Dạ, em kiểm tra và báo lại ạ.");

        drafts.reply(CONVERSATION, null, staff("chat-conversations:READ"));

        verify(orders, never()).findById(any());
        verify(orders, never()).list(any());
        verify(schedules, never()).customerSchedule(any(), any());
    }

    @Test
    @DisplayName("a model switched off is ASSISTANT_UNAVAILABLE, after the conversation was found readable")
    void modelOff() {
        transcript(false, List.of(ORDER), message(1, "CUSTOMER", "Chào em"));
        when(model.available()).thenReturn(false);

        assertThatThrownBy(() -> drafts.reply(CONVERSATION, null, sales()))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        verify(chats).recent(any(), anyInt(), eq(false), any());
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("a model failing, or answering nothing, is ASSISTANT_UNAVAILABLE too")
    void modelFailing() {
        transcript(false, List.of(), message(1, "CUSTOMER", "Chào em"));
        when(model.next(any())).thenThrow(new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED, "spent"));

        assertThatThrownBy(() -> drafts.reply(CONVERSATION, null, sales()))
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
    }

    @Test
    @DisplayName("an instruction longer than 300 characters is refused before anything is read")
    void longInstruction() {
        assertThatThrownBy(() -> drafts.reply(CONVERSATION, "x".repeat(301), sales()))
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(chats, never()).recent(any(), anyInt(), any(Boolean.class), any());
    }

    @Test
    @DisplayName("a summary reads the staff's side, internal notes included, and splits the model's JSON into lists "
            + "with the customer's name put back")
    void summary() {
        transcript(true, List.of(ORDER), message(1, "CUSTOMER", NAME + " muốn giao sớm đơn " + NUMBER),
                note(2, NOTE));
        reply("```json\n{\"summary\":\"KH#1 hỏi giao sớm đơn " + NUMBER + ".\",\"customerAsks\":[\"Giao sớm\"],"
                + "\"promisesMade\":[\"Sale báo lại KH#1 trong hôm nay\"],\"openQuestions\":[]}\n```");

        ChatDrafts.Summary summary = drafts.summary(CONVERSATION, sales());

        verify(chats).recent(CONVERSATION, ChatDrafts.SUMMARY_MESSAGES, true, sales());
        assertThat(sentToModel()).contains(NOTE, "\"internal\":true").doesNotContain(NAME);
        assertThat(summary.summary().text()).isEqualTo(NAME + " hỏi giao sớm đơn " + NUMBER + ".");
        assertThat(summary.customerAsks()).containsExactly("Giao sớm");
        assertThat(summary.promisesMade()).containsExactly("Sale báo lại " + NAME + " trong hôm nay");
        assertThat(summary.openQuestions()).isEmpty();
        assertThat(summary.orderNumbers()).containsExactly(NUMBER);
        assertThat(summary.summary().sources().getFirst().kind()).isEqualTo(Draft.Kind.CHAT);
        assertThat(summary.summary().unverifiedCodes()).isEmpty();
    }

    @Test
    @DisplayName("a summary that is not JSON is kept whole, with empty lists")
    void summaryNotJson() {
        transcript(true, List.of(), message(1, "CUSTOMER", "Chào em"));
        reply("Khách chào hỏi, chưa có yêu cầu gì.");

        ChatDrafts.Summary summary = drafts.summary(CONVERSATION, sales());

        assertThat(summary.summary().text()).isEqualTo("Khách chào hỏi, chưa có yêu cầu gì.");
        assertThat(summary.customerAsks()).isEmpty();
    }

    @Test
    @DisplayName("finding 5: the customer's short name, however it is cased or accented, its legal profile's names "
            + "and its contact people never reach the model; the contact's alias comes back as the person's name")
    void namesBeyondTheRegisteredOneAreHidden() {
        when(legalProfiles.legalProfile(CUSTOMER)).thenReturn(Optional.of(new CustomerLegalProfile(CUSTOMER,
                "Công ty TNHH Thương mại Ly Giấy Phương Nam", "0312345678", "12 Lê Lợi", "Trần Văn Bình",
                "Giám đốc", "hd@lygiay.vn", new CustomerLegalProfile.BankAccount("0071000123456", "VCB",
                "Trần Văn Bình"), 0)));
        transcript(false, List.of(ORDER),
                message(1, "CUSTOMER", "Em là Nguyễn Thị Lan Anh bên ly giấy sài gòn, anh Trần Văn Bình hỏi đơn."),
                message(2, "CUSTOMER", "Bên Ly Giấy Phương Nam cần gấp."));
        reply("Dạ chị LH#1, em báo anh LH#2 ngay ạ.");

        ChatDrafts.ReplyDraft draft = drafts.reply(CONVERSATION, "nhắn chị Nguyễn Thị Lan Anh", sales());

        String sent = sentToModel();
        assertThat(sent).doesNotContainIgnoringCase("ly giấy").doesNotContain("Nguyễn Thị Lan Anh", "Trần Văn Bình",
                "Phương Nam").contains("KH#1", "LH#1", "LH#2");
        assertThat(draft.draft().text()).isEqualTo("Dạ chị Trần Văn Bình, em báo anh Nguyễn Thị Lan Anh ngay ạ.");
        assertThat(draft.draft().unverifiedCodes()).isEmpty();
    }

    @Test
    @DisplayName("finding 10: a quote cancelled before it was sent gives its number and status, never its prices")
    void cancelledQuoteHasNoPrices() {
        when(chats.recent(eq(CONVERSATION), anyInt(), eq(false), any())).thenReturn(new ChatTranscript(CONVERSATION,
                CUSTOMER, "Báo giá", "ACTIVE", null, List.of(new ChatContextRef(ChatContextKind.QUOTE, QUOTE)),
                List.of(message(1, "CUSTOMER", "Báo giá thế nào em?")), false));
        when(quotes.quote(QUOTE)).thenReturn(Optional.of(quote("CANCELLED")));
        reply("Dạ em kiểm tra lại báo giá ạ.");

        drafts.reply(CONVERSATION, null, sales());

        assertThat(sentToModel()).contains("QT-20261120-0007", "CANCELLED").doesNotContain("4321", "98765000");
    }

    @Test
    @DisplayName("a sent quote's prices are the customer's and reach the facts")
    void sentQuoteHasPrices() {
        when(chats.recent(eq(CONVERSATION), anyInt(), eq(false), any())).thenReturn(new ChatTranscript(CONVERSATION,
                CUSTOMER, "Báo giá", "ACTIVE", null, List.of(new ChatContextRef(ChatContextKind.QUOTE, QUOTE)),
                List.of(message(1, "CUSTOMER", "Báo giá thế nào em?")), false));
        when(quotes.quote(QUOTE)).thenReturn(Optional.of(quote("SENT")));
        reply("Dạ đơn giá 4.321 đồng ạ.");

        drafts.reply(CONVERSATION, null, sales());

        assertThat(sentToModel()).contains("4321", "98765000");
    }

    @Test
    @DisplayName("finding 15: a reply with nothing usable goes to the next model once, and the business day is the "
            + "model's context")
    void escalatesAnUnusableReply() {
        transcript(false, List.of(), message(1, "CUSTOMER", "Chào em"));
        when(model.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{}", " ", List.of()),
                LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, "scripted"));
        when(second.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{}", "Dạ em chào anh chị.",
                List.of()), LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, "second"));

        ChatDrafts.ReplyDraft draft = drafts(model, second).reply(CONVERSATION, null, sales());

        assertThat(draft.draft().text()).isEqualTo("Dạ em chào anh chị.");
        assertThat(draft.draft().model()).isEqualTo("second");
        ArgumentCaptor<LanguageModel.Request> request = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(second).next(request.capture());
        assertThat(((Turn.Question) request.getValue().history().getFirst()).context())
                .isEqualTo("Business date: 27/11/2026");
    }

    private static QuoteCards.Card quote(String status) {
        return new QuoteCards.Card(QUOTE, "QT-20261120-0007", CUSTOMER, status, 1, NOW.plusSeconds(86_400), null,
                List.of(new QuoteCards.Line(UUID.randomUUID(), 1, "CUP-12OZ-LG", 10, 1000, 10_000,
                        new BigDecimal("4321"), new BigDecimal("43210000"), null)),
                new BigDecimal("98765000"), null, null, null, null, null, 0);
    }

    // ------------------------------------------------------------------------------------------------------------

    private void transcript(boolean staffSide, List<UUID> orderContexts, ChatTranscript.Message... messages) {
        when(chats.recent(eq(CONVERSATION), anyInt(), eq(staffSide), any())).thenReturn(new ChatTranscript(
                CONVERSATION, CUSTOMER, "Hỏi lịch giao", "ACTIVE", null,
                orderContexts.stream().map(id -> new ChatContextRef(ChatContextKind.ORDER, id)).toList(),
                List.of(messages), false));
    }

    private static ChatTranscript.Message message(long seq, String sender, String text) {
        return new ChatTranscript.Message(seq, sender, "TEXT", false, false, text, null, 0, NOW);
    }

    private static ChatTranscript.Message note(long seq, String text) {
        return new ChatTranscript.Message(seq, "AGENT", "INTERNAL_NOTE", true, true, text, null, 0, NOW);
    }

    private void reply(String text) {
        when(model.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{}", text, List.of()),
                LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, "scripted"));
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{}", call.getArgument(0),
                List.of()));
    }

    private String sentToModel() {
        ArgumentCaptor<LanguageModel.Request> request = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model).next(request.capture());
        assertThat(request.getValue().tools()).isEmpty();
        return request.getValue().history().stream().map(turn -> ((Turn.Question) turn).text())
                .collect(Collectors.joining("\n"));
    }

    private static final CurrentUser SALES = staff("chat-conversations:READ", "sales-orders:READ",
            "sales-order-delivery-batches:READ");

    private static CurrentUser sales() {
        return SALES;
    }

    private static CurrentUser staff(String... permissions) {
        return new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF),
                Arrays.stream(permissions).map(PermissionCode::parse).collect(Collectors.toSet()), DataScope.ALL,
                Set.of());
    }
}
