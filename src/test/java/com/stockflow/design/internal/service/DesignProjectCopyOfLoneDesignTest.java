package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A copy (revision or clone) of a design that was in no project when it was read stands alone only if the design is
 * still in no project once the customer's draft limit is locked (kltn-docs 12 BR-17, §4.7).
 */
class DesignProjectCopyOfLoneDesignTest {

    private final UUID source = UUID.randomUUID();
    private final DesignDraftJpaRepository drafts = mock(DesignDraftJpaRepository.class);
    private final DesignProjectService service = serviceOver(drafts);

    @Test
    void aSourceStillInNoProjectLetsTheCopyStandAlone() {
        when(drafts.projectIdOf(source)).thenReturn(null);

        assertThatCode(() -> service.requireStillInNoProject(source, null)).doesNotThrowAnyException();
    }

    @Test
    void aSourceThatJoinedAProjectMeanwhileIsAConflictToTryAgain() {
        when(drafts.projectIdOf(source)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.requireStillInNoProject(source, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void aCopyWrittenIntoAProjectNeedsNoCheck() {
        service.requireStillInNoProject(source, mock(DesignProjectJpaEntity.class));

        verifyNoInteractions(drafts);
        assertThat(service).isNotNull();
    }

    /** The service with only the repository the check reads: Mockito builds it without a constructor. */
    private static DesignProjectService serviceOver(DesignDraftJpaRepository drafts) {
        DesignProjectService service = Mockito.mock(DesignProjectService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "drafts", drafts);
        return service;
    }
}
