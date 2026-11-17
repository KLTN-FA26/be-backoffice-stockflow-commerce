package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.internal.entity.DesignReviewRoundDesignJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundDesignJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Duyệt tất cả" (kltn-docs 12 BR-20): a design that cannot be approved stays waiting and stops none of the others,
 * whatever fails - and a server fault's own words never reach the client.
 */
class DesignReviewRoundServiceTest {

    private final DesignReviewRoundJpaRepository rounds = mock(DesignReviewRoundJpaRepository.class);
    private final DesignReviewRoundDesignJpaRepository members = mock(DesignReviewRoundDesignJpaRepository.class);
    private final DesignReviewService reviews = mock(DesignReviewService.class);
    private final Messages messages = mock(Messages.class);
    private final DesignReviewRoundService service = new DesignReviewRoundService(rounds, members,
            mock(DesignProjectJpaRepository.class), mock(DesignDraftJpaRepository.class), reviews,
            mock(CustomerService.class), mock(DraftViews.class), messages);

    @Test
    void aDesignThatFailsInAnyWayStaysWaitingAndTheOthersAreStillApproved() {
        UUID round = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID faulty = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(rounds.findById(round)).thenReturn(Optional.of(new DesignReviewRoundJpaEntity(UUID.randomUUID(), actor,
                Instant.now())));
        when(members.findByRoundId(round)).thenReturn(List.of(new DesignReviewRoundDesignJpaEntity(round, broken),
                new DesignReviewRoundDesignJpaEntity(round, faulty), new DesignReviewRoundDesignJpaEntity(round, fine)));
        doThrow(new IllegalStateException("deadlock detected")).when(reviews).decide(eq(broken), any(), anyLong(),
                anyBoolean(), any());
        doThrow(new BusinessException(ErrorCode.STORAGE_ERROR, "No stored file with key design-renders/x.pdf"))
                .when(reviews).decide(eq(faulty), any(), anyLong(), anyBoolean(), any());
        when(messages.forCode(ErrorCode.INTERNAL_ERROR)).thenReturn("Đã có lỗi xảy ra");
        when(messages.forCode(ErrorCode.STORAGE_ERROR)).thenReturn("Lỗi lưu trữ");

        var outcomes = service.approveAll(round, actor, List.of(
                new DesignBatchSubmissionService.DesignAtVersion(broken, 1),
                new DesignBatchSubmissionService.DesignAtVersion(faulty, 1),
                new DesignBatchSubmissionService.DesignAtVersion(fine, 1)));

        assertThat(outcomes).extracting(DesignReviewRoundService.ApprovalOutcome::outcome)
                .containsExactly("NOT_APPROVED", "NOT_APPROVED", "APPROVED");
        assertThat(outcomes.get(0).errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(outcomes.get(1).message()).isEqualTo("Lỗi lưu trữ").doesNotContain("design-renders");
        verify(reviews).decide(fine, actor, 1, true, null);
    }

    @Test
    void aDesignNoLongerWaitingInTheRoundIsSkipped() {
        UUID round = UUID.randomUUID();
        UUID decided = UUID.randomUUID();
        var member = new DesignReviewRoundDesignJpaEntity(round, decided);
        member.decide("RETURNED", Instant.now());
        when(rounds.findById(round)).thenReturn(Optional.of(new DesignReviewRoundJpaEntity(UUID.randomUUID(),
                UUID.randomUUID(), Instant.now())));
        when(members.findByRoundId(round)).thenReturn(List.of(member));

        var outcomes = service.approveAll(round, UUID.randomUUID(),
                List.of(new DesignBatchSubmissionService.DesignAtVersion(decided, 3)));

        assertThat(outcomes).singleElement().satisfies(o -> assertThat(o.outcome()).isEqualTo("SKIPPED"));
    }
}
