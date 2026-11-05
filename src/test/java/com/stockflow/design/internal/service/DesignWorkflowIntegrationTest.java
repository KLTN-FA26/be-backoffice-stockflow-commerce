package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.JpaAuditingConfig;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.support.ReferenceRows;
import org.junit.jupiter.api.BeforeEach;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, DesignWorkflowService.class, DesignServiceImpl.class, DesignPreflight.class,
        PostgresContainer.class})
@Testcontainers(disabledWithoutDocker = true)
// No test transaction: the technical review runs its preflight outside any transaction and records the decision in a
// transaction of its own, so it must see what the test committed - as a request does.
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class DesignWorkflowIntegrationTest {
    @Autowired DesignWorkflowService workflow;
    @Autowired DesignDraftJpaRepository drafts;
    @Autowired DesignArtifactJpaRepository artifacts;
    @Autowired com.stockflow.design.api.DesignService designs;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @MockitoBean CurrentUserProvider users;
    @MockitoBean ProductService products;
    @MockitoBean FileStorage storage;
    @MockitoBean java.time.Clock clock;
    @MockitoBean com.stockflow.common.storage.UploadInspection inspection;
    @MockitoBean com.stockflow.common.storage.FileTransfers files;
    @MockitoBean com.stockflow.identity.api.IdentityService identities;
    @MockitoBean com.stockflow.customer.api.CustomerService customers;
    /** Real rows: the draft's customer, every actor and (since C1) its product are foreign keys. */
    private UUID owner, editor, reviewer, customer, product;
    /** The file of this test's draft: storage keys are unique, and every test commits. */
    private String key;

    @BeforeEach void referencedRows() {
        tx.executeWithoutResult(status -> {
            owner = ReferenceRows.user(entityManager);
            editor = ReferenceRows.user(entityManager);
            reviewer = ReferenceRows.user(entityManager);
            customer = ReferenceRows.customer(entityManager);
            product = ReferenceRows.product(entityManager).productId();
        });
        key = "design-renders/" + UUID.randomUUID() + ".pdf";
    }

    private DesignWorkflowService.DraftView draftWithArtifact() throws Exception {
        when(identities.isActiveUserWithAnyRole(any(), any(String[].class))).thenReturn(true);
        ownerProfile(owner, customer);
        org.mockito.Mockito.doAnswer(call -> {
            ((java.io.InputStream) call.getArgument(0)).readAllBytes();
            return null;
        }).when(inspection).requireClean(any());
        when(clock.instant()).thenReturn(Instant.parse("2026-09-17T00:00:00Z"));
        when(products.findById(any())).thenReturn(Optional.of(mock(ProductSummary.class)));
        var draft = workflow.create(customer, product, owner, editor, reviewer,
                "Custom cabinet", "Width 1800 mm, white", editor);
        byte[] bytes = com.stockflow.support.TestPdfs.blank();
        var checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact = artifacts.saveAndFlush(new DesignArtifactJpaEntity(Identifiers.newId(), draft.id(),
                new StoredFile(key, "test.pdf", "application/pdf", bytes.length, Instant.EPOCH), checksum));
        tx.executeWithoutResult(status -> {
            var row = drafts.findById(draft.id()).orElseThrow();
            row.attachArtifact(artifact.getId());
            row.editedBy(editor);
            drafts.saveAndFlush(row);
        });
        when(storage.download(key)).thenAnswer(call -> new ByteArrayInputStream(bytes));
        // Written before the store recorded SHA-256: every check falls back to hashing the bytes above.
        when(storage.stat(key)).thenReturn(
                new com.stockflow.common.storage.ObjectStat(bytes.length, "application/pdf", null));
        return workflow.get(draft.id(), owner);
    }

    @Test void aFileThePreflightRefusesCannotPassTheTechnicalReview() throws Exception {
        var draft = draftWithArtifact();
        when(storage.download(key)).thenAnswer(call -> new ByteArrayInputStream(
                "not a PDF at all".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true,
                "Looks fine")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).contains("PDF_UNREADABLE"));
        // Rejecting needs no preflight: a reviewer may always send a file back.
        assertThat(workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), false,
                "Unreadable file").technicalReviewPassed()).isFalse();
    }

    /** BR-DSG-001 gates the bundle: the PRINT_READY file the print shop gets is checked, not only the preview reviewed. */
    @Test void thePrintFileIsPreflightedNotOnlyThePreview() throws Exception {
        var draft = draftWithArtifact();
        String printKey = "design-renders/" + UUID.randomUUID() + ".pdf";
        byte[] broken = "not a PDF at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var print = artifacts.saveAndFlush(new DesignArtifactJpaEntity(Identifiers.newId(), draft.id(),
                com.stockflow.design.api.DesignArtifactRole.PRINT_READY,
                new StoredFile(printKey, "print.pdf", "application/pdf", broken.length, Instant.EPOCH),
                com.stockflow.common.storage.Checksums.sha256Hex(broken)));
        tx.executeWithoutResult(status -> {
            var row = drafts.findById(draft.id()).orElseThrow();
            row.attachArtifact(com.stockflow.design.api.DesignArtifactRole.PRINT_READY, print.getId());
            drafts.saveAndFlush(row);
        });
        when(storage.download(printKey)).thenAnswer(call -> new ByteArrayInputStream(broken));
        var current = workflow.get(draft.id(), owner);

        assertThatThrownBy(() -> workflow.review(draft.id(), reviewer, current.version(), current.currentArtifactId(),
                true, "The preview looks fine")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).contains("print.pdf").contains("PDF_UNREADABLE"));
    }

    /** Only the assigned reviewer makes the server read the bundle: anyone else is refused before a byte is read. */
    @Test void aReviewNobodyMayMakeReadsNoFile() throws Exception {
        var draft = draftWithArtifact();
        org.mockito.Mockito.clearInvocations(storage);

        assertThatThrownBy(() -> workflow.review(draft.id(), editor, draft.version(), draft.currentArtifactId(), true,
                "My own work is fine")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.FORBIDDEN));
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).download(any());
    }

    /** The owner's own customer profile, as the customer module answers it. */
    private void ownerProfile(UUID user, UUID customerId) {
        when(customers.findByUserId(user)).thenReturn(Optional.of(new com.stockflow.customer.api.CustomerSummary(
                customerId, user, "Owner", "owner@example.com", null, null, null, "ACTIVE", 0, Instant.EPOCH)));
    }

    @Test void ownerOfAnotherCustomerIsRefusedAtCreate() {
        when(identities.isActiveUserWithAnyRole(any(), any(String[].class))).thenReturn(true);
        when(products.findById(any())).thenReturn(Optional.of(mock(ProductSummary.class)));
        UUID otherCustomer = tx.execute(status -> ReferenceRows.customer(entityManager));
        ownerProfile(owner, otherCustomer);
        assertThatThrownBy(() -> workflow.create(customer, product, owner, editor, reviewer, "Cabinet", "spec", editor))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH);
                    assertThat(e.fieldErrors()).extracting(com.stockflow.common.api.FieldError::field).containsExactly("ownerUserId");
                });
    }

    @Test void ownerWithoutCustomerProfileIsRefusedAtCreate() {
        when(identities.isActiveUserWithAnyRole(any(), any(String[].class))).thenReturn(true);
        when(products.findById(any())).thenReturn(Optional.of(mock(ProductSummary.class)));
        when(customers.findByUserId(owner)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> workflow.create(customer, product, owner, editor, reviewer, "Cabinet", "spec", editor))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH));
    }

    @Test void reviewCustomerConfirmationAndReplayPreserveExactSnapshot() throws Exception {
        var draft = draftWithArtifact();
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Dimensions and structure inspected");
        var sent = workflow.submit(draft.id(), editor, reviewed.version());
        var confirmed = workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId());
        var replay = workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId());
        assertThat(replay).isEqualTo(confirmed);
        // The confirmed file is locked once, by the confirmation that made the snapshot, not by its replay.
        org.mockito.Mockito.verify(files).retainAfterCommit(key,
                com.stockflow.common.storage.RecordRetention.CONFIRMED_DESIGN);
        assertThat(confirmed.spec()).isEqualTo("Width 1800 mm, white");
        assertThat(confirmed.confirmedBy()).isEqualTo(owner);
        assertThat(workflow.get(draft.id(), owner).status()).isEqualTo("APPROVED");
        assertThat(workflow.history(draft.id(), owner, PageRequest.of(0, 20)).getContent()).hasSize(3);
        var fork = workflow.fork(draft.id(), owner);
        assertThat(fork.currentArtifactId()).isNull();
        entityManager.clear();
        assertThat(workflow.get(fork.id(), owner).parentSnapshotId()).isEqualTo(confirmed.id());
    }

    @Test void changingSpecificationInvalidatesReviewAndOldBrowserVersion() throws Exception {
        var draft = draftWithArtifact();
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Technical checks passed");
        var changed = workflow.revise(draft.id(), editor, reviewed.version(), "Width 2000 mm, cream");
        assertThat(changed.technicalReviewPassed()).isFalse();
        assertThat(changed.version()).isGreaterThan(reviewed.version());
        assertThatThrownBy(() -> workflow.submit(draft.id(), editor, reviewed.version())).isInstanceOf(BusinessException.class);
    }

    @Test void staffCannotConfirmForCustomer() throws Exception {
        var draft = draftWithArtifact();
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Technical checks passed");
        var sent = workflow.submit(draft.id(), editor, reviewed.version());
        assertThatThrownBy(() -> workflow.confirm(draft.id(), editor, sent.version(), sent.currentArtifactId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test void orderVerificationRejectsWrongCustomer() throws Exception {
        var draft = draftWithArtifact();
        var snapshot = confirm(draft);
        assertThatThrownBy(() -> designs.verifySnapshot(snapshot.id(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    @Test void orderVerificationRejectsWrongProductSku() throws Exception {
        var draft = draftWithArtifact();
        var snapshot = confirm(draft);
        when(products.containsSku(draft.productId(), "WRONG-SKU")).thenReturn(false);
        assertThatThrownBy(() -> designs.verifySnapshotForSku(snapshot.id(), draft.customerId(), "WRONG-SKU"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * Tampered bytes are caught where the file is used - packing, prepress - not at checkout, which reads the
     * database only and so never waits on the object store (BR-DSG-003, BR-PRD-02).
     */
    @Test void tamperedStorageBytesAreRejectedWhereTheFileIsUsed() throws Exception {
        var draft = draftWithArtifact();
        var snapshot = confirm(draft);
        when(storage.download(key)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        org.mockito.Mockito.clearInvocations(storage);

        assertThat(designs.verifySnapshot(snapshot.id(), draft.customerId()).checksum()).isEqualTo(snapshot.checksum());
        org.mockito.Mockito.verifyNoInteractions(storage);

        assertThat(designs.verifyForFulfillment(snapshot.id(), snapshot.checksum()).matching()).isFalse();
        when(storage.download(key)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(designs.verifyForPrinting(snapshot.id(), snapshot.checksum()).matching()).isFalse();
    }

    /** A file whose stored digest no longer matches is refused at confirmation: no snapshot is made of it. */
    @Test void confirmationRefusesAFileThatChangedInStorage() throws Exception {
        var draft = draftWithArtifact();
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Technical checks passed");
        var sent = workflow.submit(draft.id(), editor, reviewed.version());
        when(storage.stat(key)).thenReturn(new com.stockflow.common.storage.ObjectStat(3,
                "application/pdf", com.stockflow.common.storage.Checksums.sha256Hex(new byte[]{1, 2, 3})));
        org.mockito.Mockito.clearInvocations(storage);   // the review's preflight read the file; the confirmation must not

        assertThatThrownBy(() -> workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId()))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).download(any());
    }

    @Test void databaseRejectsSnapshotMutation() throws Exception {
        var draft = draftWithArtifact();
        var snapshot = confirm(draft);
        assertThatThrownBy(() -> jdbc.update("update design.design_snapshot set spec = ? where id = ?", "tampered", snapshot.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private DesignWorkflowService.SnapshotView confirm(DesignWorkflowService.DraftView draft) {
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Technical checks passed");
        var sent = workflow.submit(draft.id(), editor, reviewed.version());
        return workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId());
    }
}
