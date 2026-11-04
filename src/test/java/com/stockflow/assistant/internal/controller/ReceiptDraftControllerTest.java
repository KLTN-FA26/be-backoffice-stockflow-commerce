package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.ReceiptDraftResponse;
import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.assistant.internal.domain.ReceiptDraft;
import com.stockflow.assistant.internal.service.AssistantActions;
import com.stockflow.assistant.internal.service.ReceiptDraftService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The endpoint's declarations and its thin work: the receipt screen's permission, the questions' rate-limit budget, a
 * customer refused before anything, and the draft passed through field by field. The guard with the real security
 * chain is in {@code AssistantReceiptDraftIntegrationTest}.
 */
class ReceiptDraftControllerTest {

    private static final CurrentUser CLERK = new CurrentUser(UUID.randomUUID(), "kho",
            Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE, Set.of("HCM"));

    @Test
    @DisplayName("guarded as creating a goods receipt is: procurement-goods-receipts:CREATE, the default scope")
    void theReceiptScreensPermission() {
        RequiresPermission guard = handler().getAnnotation(RequiresPermission.class);

        assertThat(guard.resource()).isEqualTo("procurement-goods-receipts");
        assertThat(guard.action()).isEqualTo(Action.CREATE);
        assertThat(guard.scope()).isEqualTo(DataScope.ALL);
    }

    @Test
    @DisplayName("draws on the questions' budget: the same bucket, limit, window and key as asking")
    void theQuestionsBudget() throws Exception {
        RateLimit reading = handler().getAnnotation(RateLimit.class);
        RateLimit asking = Arrays.stream(AssistantController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("ask")).findFirst().orElseThrow()
                .getAnnotation(RateLimit.class);

        assertThat(List.of(reading.bucket(), reading.limit(), reading.perSeconds(), reading.key()))
                .isEqualTo(List.of(asking.bucket(), asking.limit(), asking.perSeconds(), asking.key()));
    }

    @Test
    @DisplayName("a portal customer is refused before the photo is looked at")
    void customersAreRefused() {
        ReceiptDraftService drafts = mock(ReceiptDraftService.class);
        CurrentUser customer = new CurrentUser(UUID.randomUUID(), "khach", Set.of(Role.CUSTOMER), Set.of(),
                DataScope.ALL, Set.of());

        assertThatThrownBy(() -> new ReceiptDraftController(drafts, mock(AssistantActions.class))
                .draft(customer, photo(), "PO-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(drafts);
    }

    @Test
    @DisplayName("the upload reaches the service as it came, and the draft comes back field by field")
    void passesThrough() {
        ReceiptDraftService drafts = mock(ReceiptDraftService.class);
        ReceiptDraft draft = new ReceiptDraft("PO-1", "Giấy Việt",
                List.of(new ReceiptDraft.Line(2, "INK-FOOD-BLUE", 500, 300, 400, "L1", LocalDate.of(2027, 11, 7),
                        "Mực in xanh 400", List.of(ReceiptDraft.OVER_DELIVERY))),
                List.of(new ReceiptDraft.Unmatched("Pallet", 2)), List.of());
        when(drafts.draft(eq("PO-1"), any())).thenReturn(draft);
        AssistantActions actions = mock(AssistantActions.class);
        UUID suggestion = UUID.randomUUID();
        when(actions.suggestGoodsReceipt(CLERK, draft)).thenReturn(java.util.Optional.of(
                new AssistantActions.ActionView(suggestion, AssistantAction.Kind.GOODS_RECEIPT_DRAFT,
                        AssistantAction.Status.SUGGESTED, null, null, null, null, null, null, null, null)));

        ApiResponse<ReceiptDraftResponse> response = new ReceiptDraftController(drafts, actions)
                .draft(CLERK, photo(), "PO-1");

        ArgumentCaptor<FileUpload> upload = ArgumentCaptor.forClass(FileUpload.class);
        verify(drafts).draft(eq("PO-1"), upload.capture());
        assertThat(upload.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(upload.getValue().sizeBytes()).isEqualTo(4);
        assertThat(response.data()).isEqualTo(new ReceiptDraftResponse("PO-1", "Giấy Việt",
                List.of(new ReceiptDraftResponse.Line(2, "INK-FOOD-BLUE", 500, 300, 400, "L1",
                        LocalDate.of(2027, 11, 7), "Mực in xanh 400", List.of("OVER_DELIVERY"))),
                List.of(new ReceiptDraftResponse.Unmatched("Pallet", 2)), List.of(), suggestion));
    }

    private static Method handler() {
        return Arrays.stream(ReceiptDraftController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("draft")).findFirst().orElseThrow();
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "note.jpg", "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
    }
}
