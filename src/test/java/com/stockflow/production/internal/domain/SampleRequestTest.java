package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SampleApproved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 03-state-machines §17 on the aggregate: every edge, every refusal leaves the round untouched. */
class SampleRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private final UUID customer = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final SampleSpecification spec = new SampleSpecification(UUID.randomUUID(), new Sku("CUP-PP-500"), 5,
            LocalDate.of(2026, 10, 20));

    private SampleRequest draft() {
        return SampleRequest.draft(UUID.randomUUID(), "SR-20261012-0001", customer, null, spec);
    }

    private SampleRequest sent() {
        SampleRequest sample = draft();
        sample.submit();
        sample.productionStarted();
        sample.productionCompleted();
        sample.markSent(NOW);
        return sample;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void onlyTheDocumentedEdgesExist() {
        Map<SampleStatus, Set<SampleStatus>> allowed = Map.of(
                SampleStatus.DRAFT, EnumSet.of(SampleStatus.SUBMITTED, SampleStatus.CANCELLED),
                SampleStatus.SUBMITTED, EnumSet.of(SampleStatus.IN_PRODUCTION),
                SampleStatus.IN_PRODUCTION, EnumSet.of(SampleStatus.READY),
                SampleStatus.READY, EnumSet.of(SampleStatus.SENT_TO_CUSTOMER),
                SampleStatus.SENT_TO_CUSTOMER, EnumSet.of(SampleStatus.APPROVED, SampleStatus.CHANGES_REQUESTED),
                SampleStatus.CHANGES_REQUESTED, EnumSet.noneOf(SampleStatus.class),
                SampleStatus.APPROVED, EnumSet.noneOf(SampleStatus.class),
                SampleStatus.CANCELLED, EnumSet.noneOf(SampleStatus.class));
        for (SampleStatus from : SampleStatus.values()) {
            for (SampleStatus to : SampleStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void aDraftIsTheFirstRoundWithoutPredecessor() {
        SampleRequest sample = draft();
        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(sample.round()).isEqualTo(1);
        assertThat(sample.previousRoundId()).isNull();
        assertThat(sample.pullDomainEvents()).isEmpty();
    }

    @Test
    void walksTheHappyPathAndApprovalAnnouncesTheDesignOnTheBlank() {
        SampleRequest sample = sent();
        assertThat(sample.sentAt()).isEqualTo(NOW);

        sample.approve(actor, NOW);

        assertThat(sample.status()).isEqualTo(SampleStatus.APPROVED);
        assertThat(sample.respondedBy()).isEqualTo(actor);
        assertThat(sample.respondedAt()).isEqualTo(NOW);
        assertThat(sample.pullDomainEvents()).singleElement().satisfies(event ->
                assertThat(((SampleEvent) event).payload()).isEqualTo(new SampleApproved(sample.id(), customer,
                        spec.designSnapshotId(), "CUP-PP-500")));
    }

    @Test
    void approvalIsTerminalAndLocksTheSpecification() {
        SampleRequest sample = sent();
        sample.approve(actor, NOW);
        sample.pullDomainEvents();

        assertThatThrownBy(() -> sample.approve(actor, NOW)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(() -> sample.requestChanges("again", actor, NOW)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(() -> sample.edit(spec)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThat(sample.pullDomainEvents()).as("a refused second approval announces nothing").isEmpty();
    }

    @Test
    void changesKeepTheFeedbackAndTheNextRoundIsANewRequestPointingBack() {
        SampleRequest first = sent();
        first.requestChanges("  Logo 20% larger  ", actor, NOW);

        assertThat(first.status()).isEqualTo(SampleStatus.CHANGES_REQUESTED);
        assertThat(first.customerFeedback()).isEqualTo("Logo 20% larger");
        assertThat(first.pullDomainEvents()).isEmpty();

        SampleSpecification revised = new SampleSpecification(UUID.randomUUID(), spec.blankSku(), 3, null);
        SampleRequest second = first.nextRound(UUID.randomUUID(), "SR-20261012-0002", revised);

        assertThat(second.round()).isEqualTo(2);
        assertThat(second.previousRoundId()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(second.customerId()).isEqualTo(customer);
        assertThat(second.specification()).isEqualTo(revised);
        assertThat(first.status()).as("the reviewed round is not reopened").isEqualTo(SampleStatus.CHANGES_REQUESTED);
        assertThat(first.specification()).isEqualTo(spec);
    }

    @ParameterizedTest
    @EnumSource(value = SampleStatus.class, names = "CHANGES_REQUESTED", mode = EnumSource.Mode.EXCLUDE)
    void onlyARoundWithChangesRequestedHasANextRound(SampleStatus status) {
        SampleRequest sample = SampleRequest.restore(UUID.randomUUID(), "SR-1", customer, null, null, 1, spec, status,
                NOW, NOW, actor, null, null);
        assertThatThrownBy(() -> sample.nextRound(UUID.randomUUID(), "SR-2", spec))
                .extracting(SampleRequestTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void askingForChangesMustSayWhatAndLeavesTheRoundAsItWasOtherwise(String feedback) {
        SampleRequest sample = sent();
        assertThatThrownBy(() -> sample.requestChanges(feedback, actor, NOW)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> sample.requestChanges("x".repeat(SampleRequest.MAX_FEEDBACK + 1), actor, NOW))
                .extracting(SampleRequestTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(sample.status()).isEqualTo(SampleStatus.SENT_TO_CUSTOMER);
        assertThat(sample.respondedAt()).isNull();
    }

    @Test
    void onlyADraftIsEditedOrCancelledAndCancellingNeedsAReason() {
        SampleRequest sample = draft();
        SampleSpecification other = new SampleSpecification(UUID.randomUUID(), new Sku("BOX-KRAFT-01"), 2, null);
        sample.edit(other);
        assertThat(sample.specification()).isEqualTo(other);

        assertThatThrownBy(() -> sample.cancel(" ")).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        sample.cancel("Customer chose a stock design");
        assertThat(sample.status()).isEqualTo(SampleStatus.CANCELLED);
        assertThat(sample.cancellationReason()).isEqualTo("Customer chose a stock design");

        SampleRequest submitted = draft();
        submitted.submit();
        assertThatThrownBy(() -> submitted.cancel("too late")).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(() -> submitted.edit(other)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThat(submitted.specification()).isEqualTo(spec);
    }

    @Test
    void stepsCannotBeSkippedNorAnsweredBeforeTheSampleWasSent() {
        SampleRequest sample = draft();
        assertThatThrownBy(() -> sample.markSent(NOW)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(sample::productionCompleted).as("a draft has no production order")
                .extracting(SampleRequestTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        sample.submit();
        assertThatThrownBy(() -> sample.approve(actor, NOW)).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(sample::submit).as("a second submit").extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThat(sample.status()).isEqualTo(SampleStatus.SUBMITTED);
        assertThat(sample.pullDomainEvents()).isEmpty();
    }

    @Test
    void theRoundChainAndQuantityMirrorTheTableChecks() {
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), "SR-1", customer, null, UUID.randomUUID(), 1,
                spec, SampleStatus.DRAFT, null, null, null, null, null))
                .as("ck_sample_request_round_chain").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleRequest.restore(UUID.randomUUID(), "SR-2", customer, null, null, 2, spec,
                SampleStatus.DRAFT, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampleSpecification(UUID.randomUUID(), new Sku("CUP-PP-500"), 0, null))
                .as("ck_sample_request_quantity").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampleSpecification(UUID.randomUUID(), new Sku("CUP-PP-500"),
                SampleSpecification.MAX_QUANTITY + 1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workshopReportsAreIdempotentAndACompletionImpliesTheStart() {
        SampleRequest twice = draft();
        twice.submit();
        assertThat(twice.productionStarted()).isTrue();
        assertThat(twice.productionStarted()).as("reported again").isFalse();
        assertThat(twice.status()).isEqualTo(SampleStatus.IN_PRODUCTION);
        assertThat(twice.productionCompleted()).isTrue();
        assertThat(twice.productionCompleted()).isFalse();
        assertThat(twice.productionStarted()).as("a late start report does not go back").isFalse();
        assertThat(twice.status()).isEqualTo(SampleStatus.READY);

        SampleRequest direct = draft();
        direct.submit();
        assertThat(direct.productionCompleted()).isTrue();
        assertThat(direct.status()).isEqualTo(SampleStatus.READY);

        SampleRequest answered = sent();
        answered.approve(actor, NOW);
        assertThat(answered.productionCompleted()).isFalse();
        assertThat(answered.status()).isEqualTo(SampleStatus.APPROVED);

        SampleRequest cancelled = draft();
        cancelled.cancel("not needed");
        assertThatThrownBy(cancelled::productionStarted).extracting(SampleRequestTest::code)
                .isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
    }
}
