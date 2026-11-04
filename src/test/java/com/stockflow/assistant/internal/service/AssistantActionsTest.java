package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.assistant.internal.domain.AssistantActionRepository;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.api.GoodsReceiptDrafting;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SupplierItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@link AssistantActions} decides before any document module is called. */
class AssistantActionsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");

    private final AssistantActionRepository repository = mock(AssistantActionRepository.class);
    private final GoodsReceiptDrafting receipts = mock(GoodsReceiptDrafting.class);
    private final ReceiptDraftService receiptDrafts = mock(ReceiptDraftService.class);
    private final AssistantActions actions = new AssistantActions(repository, mock(ProcurementService.class),
            mock(SupplierItemService.class), receipts, receiptDrafts,
            new ObjectMapper().registerModule(new JavaTimeModule()), Clock.fixed(NOW, ZoneOffset.UTC),
            AssistantTelemetry.NONE);

    private static CurrentUser user(PermissionCode... granted) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(granted),
                DataScope.ALL, Set.of());
    }

    @Test
    @DisplayName("a delivery note's reading is kept under its order's key and supersedes nothing; one that read no "
            + "line of the order is not kept, having nothing to confirm")
    void aReadingIsKeptUnderItsOrder() {
        CurrentUser clerk = user(PermissionCode.of(AssistantActions.GOODS_RECEIPTS, Action.CREATE));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        ReceiptDraft.Line line = new ReceiptDraft.Line(1, "CUP-12OZ", 100, 100, 100, null, null, "Ly 12oz", List.of());

        assertThat(actions.suggestGoodsReceipt(clerk,
                new ReceiptDraft(" po-1 ", "Supplier", List.of(line), List.of(), List.of()))).isPresent();
        assertThat(actions.suggestGoodsReceipt(clerk,
                new ReceiptDraft("PO-1", "Supplier", List.of(), List.of(), List.of("NOTHING_READ")))).isEmpty();

        ArgumentCaptor<AssistantAction> saved = ArgumentCaptor.forClass(AssistantAction.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().proposalKey()).isEqualTo("GOODS_RECEIPT_DRAFT:PO-1");
        assertThat(saved.getValue().conversationId()).isNull();
        verify(repository, never()).supersedeCovered(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an answer's purchase-order proposal supersedes the owner's open ones of its supplier that it orders "
            + "all of, named by its SKUs upper case; other types are not suggested")
    void proposalsSupersedeWhatTheyCover() {
        UUID owner = UUID.randomUUID();
        AnswerSources.Proposal order = new AnswerSources.Proposal("PURCHASE_ORDER_DRAFT", "SUP-A", "A", NOW,
                List.of(new AnswerSources.ProposalLine("cup-12oz", 100), new AnswerSources.ProposalLine("LID-90", 50)));
        AnswerSources.Proposal other = new AnswerSources.Proposal("TRANSFER_DRAFT", "SUP-B", "B", NOW, List.of());

        actions.suggestPurchaseOrders(owner, UUID.randomUUID(), UUID.randomUUID(), List.of(order, other));

        var sequence = inOrder(repository);
        sequence.verify(repository).supersedeCovered(owner, AssistantAction.Kind.PURCHASE_ORDER_DRAFT,
                "PURCHASE_ORDER_DRAFT:SUP-A", Set.of("CUP-12OZ", "LID-90"), NOW);
        sequence.verify(repository).suggestAll(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssistantAction>> suggested = ArgumentCaptor.forClass(List.class);
        verify(repository).suggestAll(suggested.capture());
        assertThat(suggested.getValue()).singleElement()
                .satisfies(action -> assertThat(action.proposalKey()).isEqualTo("PURCHASE_ORDER_DRAFT:SUP-A"));
    }

    @Test
    @DisplayName("confirming a receipt needs the receipt screen's UPDATE too, refused before anything is read or locked")
    void aReceiptNeedsTheCountingPermission() {
        CurrentUser createOnly = user(PermissionCode.of(AssistantActions.GOODS_RECEIPTS, Action.CREATE));

        assertThatThrownBy(() -> actions.confirmGoodsReceipt(UUID.randomUUID(), createOnly,
                new AssistantActions.GoodsReceiptChoice(null, null, "HCM-RCV01",
                        List.of(new AssistantActions.ReceiptLine(1, 5, null, null, null, null)))))
                .isInstanceOf(PermissionDeniedException.class);
        verifyNoInteractions(repository, receipts, receiptDrafts);
    }

    @Test
    @DisplayName("no proposal of a type it can confirm: nothing is written")
    void nothingToSuggest() {
        actions.suggestPurchaseOrders(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new AnswerSources.Proposal("TRANSFER_DRAFT", "SUP-B", "B", NOW, List.of())));

        verifyNoInteractions(repository);
    }
}
