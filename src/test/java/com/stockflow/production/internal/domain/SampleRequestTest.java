package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.production.api.SampleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleRequestTest {
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-08T04:00:00Z");

    @ParameterizedTest
    @EnumSource(SampleStatus.class)
    void onlyDocumentedTransitionsAreAllowed(SampleStatus from) {
        EnumSet<SampleStatus> expected = switch (from) {
            case DRAFT -> EnumSet.of(SampleStatus.SUBMITTED, SampleStatus.CANCELLED);
            case SUBMITTED -> EnumSet.of(SampleStatus.IN_PRODUCTION);
            case IN_PRODUCTION -> EnumSet.of(SampleStatus.READY);
            case READY -> EnumSet.of(SampleStatus.SENT_TO_CUSTOMER);
            case SENT_TO_CUSTOMER -> EnumSet.of(SampleStatus.APPROVED, SampleStatus.CHANGES_REQUESTED);
            case CHANGES_REQUESTED -> EnumSet.of(SampleStatus.DRAFT);
            case APPROVED, CANCELLED -> EnumSet.noneOf(SampleStatus.class);
        };
        for (var to : SampleStatus.values()) {
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected.contains(to));
        }
    }

    @Test
    void approvalLocksTheExactCustomerDesignAndBlankAndRecordsOneContractEvent() {
        var sample = sentSample();
        var specification = sample.specification();
        sample.approve(ACTOR, NOW);

        assertThat(sample.status()).isEqualTo(SampleStatus.APPROVED);
        assertThat(sample.reviews()).singleElement().satisfies(review -> {
            assertThat(review.specification()).isEqualTo(specification);
            assertThat(review.recordedBy()).isEqualTo(ACTOR);
            assertThat(review.recordedAt()).isEqualTo(NOW);
        });
        assertThat(sample.pullDomainEvents()).singleElement().satisfies(event -> {
            var envelope = (SampleEvent) event;
            assertThat(envelope.occurredAt()).isEqualTo(NOW);
            assertThat(envelope.payload()).isEqualTo(new SampleApproved(sample.id(), CUSTOMER,
                    specification.designSnapshotId(), "CUP-001"));
        });
        assertThatThrownBy(() -> sample.approve(ACTOR, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> sample.edit(specification())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(sample::revise).isInstanceOf(ConflictException.class);
        assertThatThrownBy(sample::cancel).isInstanceOf(ConflictException.class);
        assertThat(sample.pullDomainEvents()).isEmpty();
        assertThat(sample.specification()).isEqualTo(specification);
    }

    @Test
    void revisionPreservesFeedbackAndOnlyIncrementsRoundWhenSalesReopensDraft() {
        var sample = sentSample();
        var first = sample.specification();
        sample.requestChanges("  Make the logo larger  ", ACTOR, NOW);
        assertThat(sample.round()).isEqualTo(1);
        assertThat(sample.pullDomainEvents()).isEmpty();
        sample.revise();
        assertThat(sample.round()).isEqualTo(2);
        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(sample.warehouseId()).isNull();
        assertThatThrownBy(sample::revise).isInstanceOf(ConflictException.class);
        sample.edit(specification());
        assertThat(sample.reviews()).singleElement().satisfies(review -> {
            assertThat(review.round()).isEqualTo(1);
            assertThat(review.feedback()).isEqualTo("Make the logo larger");
            assertThat(review.specification()).isEqualTo(first);
        });
        assertThatThrownBy(() -> sample.reviews().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidFeedbackOrActorDoesNotPartiallyChangeState() {
        var sample = sentSample();
        long version = sample.version();
        assertThatThrownBy(() -> sample.requestChanges("  ", ACTOR, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample.approve(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThat(sample.status()).isEqualTo(SampleStatus.SENT_TO_CUSTOMER);
        assertThat(sample.version()).isEqualTo(version);
        assertThat(sample.reviews()).isEmpty();
        assertThat(sample.pullDomainEvents()).isEmpty();
    }

    @Test
    void staleRoundCannotApproveANewerRoundOrAcceptAnOldProductionCallback() {
        var sample = sentSample();
        sample.requestChanges("Another color", ACTOR, NOW);
        sample.revise();
        var stale = assertThrows(ConflictException.class, () -> sample.requireRevision(1, sample.version()));
        assertThat(stale.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        sample.submit(WAREHOUSE);
        assertThatThrownBy(() -> sample.startProduction(1)).isInstanceOf(ConflictException.class);
        sample.startProduction(2);
        assertThatThrownBy(() -> sample.markReady(1)).isInstanceOf(ConflictException.class);
        assertThat(sample.status()).isEqualTo(SampleStatus.IN_PRODUCTION);
    }

    @Test
    void optimisticRevisionRejectsStaleVersion() {
        var sample = draft();
        sample.edit(specification());
        var conflict = assertThrows(ConflictException.class, () -> sample.requireRevision(1, 0));
        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        sample.requireRevision(1, 1);
    }

    @Test
    void onlyADraftCanBeCancelledAndSubmittedSpecificationsCannotChange() {
        var cancelled = draft();
        cancelled.cancel();
        assertThatThrownBy(() -> cancelled.submit(WAREHOUSE)).isInstanceOf(ConflictException.class);
        var submitted = draft();
        submitted.submit(WAREHOUSE);
        assertThatThrownBy(submitted::cancel).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> submitted.edit(specification())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(submitted::sendToCustomer).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> submitted.approve(ACTOR, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void restoringAnApprovalPreservesItsIdentityAndLockWithoutRepublishing() {
        var sample = sentSample();
        sample.approve(ACTOR, NOW);
        var restored = SampleRequest.restore(sample.id(), sample.customerId(), sample.quoteId(), sample.createdBy(),
                sample.specification(), sample.status(), sample.round(), sample.version(), sample.warehouseId(), sample.reviews());
        assertThat(restored.id()).isEqualTo(sample.id());
        assertThat(restored.reviews()).isEqualTo(sample.reviews());
        assertThat(restored.pullDomainEvents()).isEmpty();
        assertThatThrownBy(() -> restored.edit(specification())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> restored.approve(ACTOR, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void restoringCannotForgeAnApprovalOrLoseRoundHistory() {
        var specification = specification();
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), CUSTOMER, null, ACTOR, specification,
                SampleStatus.APPROVED, 1, 5, WAREHOUSE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), CUSTOMER, null, ACTOR, specification,
                SampleStatus.DRAFT, 2, 5, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        var approval = new SampleReview(1, specification, SampleReview.Decision.APPROVED, null, ACTOR, NOW);
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), CUSTOMER, null, ACTOR, specification(),
                SampleStatus.APPROVED, 1, 5, WAREHOUSE, List.of(approval))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoringSubmittedRoundRequiresWarehouse() {
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), CUSTOMER, null, ACTOR, specification(),
                SampleStatus.SUBMITTED, 1, 1, null, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void quantityMustBePositive(int quantity) {
        assertThatThrownBy(() -> new SampleSpecification(UUID.randomUUID(), "a".repeat(64),
                new Sku("CUP-001"), quantity, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void checksumMustBeSha256(String checksum) {
        assertThatThrownBy(() -> new SampleSpecification(UUID.randomUUID(), checksum,
                new Sku("CUP-001"), 1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SampleSpecification specification() {
        return new SampleSpecification(UUID.randomUUID(), "a".repeat(64), new Sku("CUP-001"), 2,
                LocalDate.of(2026, 10, 20));
    }

    private static SampleRequest draft() {
        return SampleRequest.draft(CUSTOMER, null, ACTOR, specification());
    }

    private static SampleRequest sentSample() {
        var sample = draft();
        sample.submit(WAREHOUSE);
        sample.startProduction(1);
        sample.markReady(1);
        sample.sendToCustomer();
        return sample;
    }
}
