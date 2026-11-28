package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.api.CustomerInstallments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reminder draft (work features 2.2): what the model is given and what it is not, the customer's alias put back,
 * the grounding check, and a model off or failing as 503.
 */
class ReminderDraftsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID INSTALLMENT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    private final CustomerInstallments installments = mock(CustomerInstallments.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final LanguageModel second = mock(LanguageModel.class);

    private ReminderDrafts drafts(LanguageModel... models) {
        List<ModelRouter.Candidate> candidates = new java.util.ArrayList<>();
        for (int n = 0; n < models.length; n++) {
            candidates.add(new ModelRouter.Candidate("model-" + n, models[n]));
        }
        ModelRouter router = new ModelRouter(candidates, new ModelRoutingProperties(List.of(), true, null),
                ZoneOffset.UTC, CLOCK);
        return new ReminderDrafts(installments, new DraftWriter(router, customers, mock(CustomerLegalProfiles.class),
                mock(IdentityService.class), CLOCK), new ObjectMapper(), CLOCK);
    }

    private static CustomerInstallments.Transfer installment(String status, long outstanding, boolean overdue) {
        return installment(status, outstanding, overdue, LocalDate.of(2026, 11, 12));
    }

    private static CustomerInstallments.Transfer installment(String status, long outstanding, boolean overdue,
                                                             LocalDate due) {
        return new CustomerInstallments.Transfer(INSTALLMENT, CUSTOMER, UUID.randomUUID(), "HD-20261020-000310-1",
                ORDER, "SO-20261020-000310", 2, "Đợt 2 - 50% sau giao hàng", BigDecimal.valueOf(100_000_000),
                BigDecimal.valueOf(outstanding), "VND", due, status, overdue, "HD-20261020-000310-1 DOT2");
    }

    private static LanguageModel.Reply reply(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{}", text, List.of()), LanguageModel.StopReason.END_TURN,
                LanguageModel.Usage.NONE, "gemini-test");
    }

    @BeforeEach
    void customer() {
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(new CustomerSummary(CUSTOMER, null,
                "Công ty TNHH Bao Bì Minh Phát", null, null, "0109876543", null, "ACTIVE", 0, CLOCK.instant())));
        when(model.available()).thenReturn(true);
        when(second.available()).thenReturn(true);
    }

    @Test
    @DisplayName("the model sees the alias and the installment's facts only; the draft names the customer, and every "
            + "code and figure in it is grounded")
    void drafts() throws Exception {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("PARTIALLY_PAID", 62_500_000,
                true)));
        when(model.next(any())).thenReturn(reply("""
                Kính gửi KH#1,
                Đợt 2 - 50% sau giao hàng của hợp đồng HD-20261020-000310-1 còn 62.500.000 đồng, hạn 12/11/2026, \
                đã quá hạn 15 ngày. Quý khách vui lòng ghi nội dung chuyển khoản HD-20261020-000310-1 DOT2.
                Trân trọng,
                Phòng Kinh doanh"""));

        Draft draft = drafts(model).draft(INSTALLMENT, ReminderDrafts.Tone.FIRM);

        ArgumentCaptor<LanguageModel.Request> sent = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model).next(sent.capture());
        String question = ((Turn.Question) sent.getValue().history().getFirst()).text();
        assertThat(question).contains("\"customer\":\"KH#1\"", "HD-20261020-000310-1", "62500000", "12/11/2026",
                "\"daysOverdue\":15", "\"tone\":\"FIRM\"", "HD-20261020-000310-1 DOT2")
                .doesNotContain("Minh Phát", "Minh Phat", "0109876543", CUSTOMER.toString());
        assertThat(sent.getValue().tools()).isEmpty();
        assertThat(sent.getValue().toolsAllowed()).isFalse();
        assertThat(draft.text()).startsWith("Kính gửi Công ty TNHH Bao Bì Minh Phát,").doesNotContain("KH#1");
        assertThat(draft.unverifiedCodes()).isEmpty();
        assertThat(draft.model()).isEqualTo("gemini-test");
        assertThat(draft.generatedAt()).isEqualTo(CLOCK.instant());
        assertThat(draft.sources()).extracting(Draft.Source::kind, Draft.Source::href).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Draft.Kind.CONTRACT, null),
                org.assertj.core.groups.Tuple.tuple(Draft.Kind.ORDER, "/admin/orders/" + ORDER),
                org.assertj.core.groups.Tuple.tuple(Draft.Kind.INSTALLMENT, "/admin/payment/installments/"
                        + INSTALLMENT));
    }

    @Test
    @DisplayName("a figure or a code the facts do not hold is flagged, never removed")
    void flagsInventions() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("DUE", 100_000_000, false)));
        when(model.next(any())).thenReturn(reply("Kính gửi KH#1, hợp đồng HD-20261020-000999-1 còn 120.000.000 "
                + "đồng, phí phạt 5.000.000 đồng."));

        Draft draft = drafts(model).draft(INSTALLMENT, null);

        assertThat(draft.text()).contains("HD-20261020-000999-1", "120.000.000");
        assertThat(draft.unverifiedCodes()).contains("HD-20261020-000999-1", "120.000.000", "5.000.000");
    }

    @Test
    @DisplayName("a model failing before it replies moves the draft on to the next; none left is 503 "
            + "ASSISTANT_UNAVAILABLE")
    void unavailable() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("DUE", 100_000_000, false)));
        when(model.next(any())).thenThrow(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "down"));
        when(second.next(any())).thenReturn(reply("Kính gửi KH#1, hợp đồng HD-20261020-000310-1."));

        assertThat(drafts(model, second).draft(INSTALLMENT, null).text()).contains("Bao Bì Minh Phát");
        assertThatThrownBy(() -> drafts(model).draft(INSTALLMENT, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("an empty reply is not a draft: 503, not an empty text")
    void emptyReply() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("DUE", 100_000_000, false)));
        when(model.next(any())).thenReturn(reply("  "));

        assertThatThrownBy(() -> drafts(model).draft(INSTALLMENT, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("nobody is chased for an installment paid or cancelled, and an unknown one is 404; the model is not "
            + "asked")
    void notOwing() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("PAID", 0, false)));
        UUID unknown = UUID.randomUUID();
        when(installments.transfer(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> drafts(model).draft(INSTALLMENT, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE));
        assertThatThrownBy(() -> drafts(model).draft(unknown, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSTALLMENT_NOT_FOUND));
        verify(model, org.mockito.Mockito.never()).next(any());
        verifyNoInteractions(customers);
    }

    @Test
    @DisplayName("finding 4: no letter asks for money not owed yet - an installment with no due day (it falls due with "
            + "a delivery) or due beyond the collections list's week is 409 INSTALLMENT_NOT_DUE_SOON, the model not "
            + "asked")
    void notDueSoon() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("NOT_DUE", 100_000_000, false,
                null)));
        assertThatThrownBy(() -> drafts(model).draft(INSTALLMENT, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSTALLMENT_NOT_DUE_SOON));

        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("NOT_DUE", 100_000_000, false,
                LocalDate.of(2026, 12, 5))));
        assertThatThrownBy(() -> drafts(model).draft(INSTALLMENT, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSTALLMENT_NOT_DUE_SOON));
        verify(model, org.mockito.Mockito.never()).next(any());
    }

    @Test
    @DisplayName("an installment due within the week, the last day of the window included, is drafted")
    void dueWithinTheWeek() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(installment("NOT_DUE", 100_000_000, false,
                LocalDate.of(2026, 12, 4))));
        when(model.next(any())).thenReturn(reply("Kính gửi KH#1, đợt 2 đến hạn 04/12/2026."));

        assertThat(drafts(model).draft(INSTALLMENT, null).text()).contains("Bao Bì Minh Phát", "04/12/2026");
    }

    @Test
    @DisplayName("finding 5: the customer's name without its legal form, typed in lower case into the installment's "
            + "name, reaches the model as its alias")
    void shortNameInTheInstallmentNameIsHidden() {
        when(installments.transfer(INSTALLMENT)).thenReturn(Optional.of(new CustomerInstallments.Transfer(INSTALLMENT,
                CUSTOMER, UUID.randomUUID(), "HD-20261020-000310-1", ORDER, "SO-20261020-000310", 2,
                "Đợt 2 - bao bì minh phát trả sau giao", BigDecimal.valueOf(100_000_000),
                BigDecimal.valueOf(100_000_000), "VND", LocalDate.of(2026, 11, 30), "DUE", false,
                "HD-20261020-000310-1 DOT2")));
        when(model.next(any())).thenReturn(reply("Kính gửi KH#1."));

        drafts(model).draft(INSTALLMENT, null);

        ArgumentCaptor<LanguageModel.Request> sent = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model).next(sent.capture());
        assertThat(((Turn.Question) sent.getValue().history().getFirst()).text())
                .contains("Đợt 2 - KH#1 trả sau giao").doesNotContainIgnoringCase("minh phát");
    }
}
