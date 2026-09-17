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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
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
@Import({JpaAuditingConfig.class, DesignWorkflowService.class, DesignServiceImpl.class})
@Testcontainers(disabledWithoutDocker = true)
class DesignWorkflowIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired DesignWorkflowService workflow;
    @Autowired DesignDraftJpaRepository drafts;
    @Autowired DesignArtifactJpaRepository artifacts;
    @Autowired com.stockflow.design.api.DesignService designs;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider users;
    @MockitoBean ProductService products;
    @MockitoBean FileStorage storage;
    @MockitoBean java.time.Clock clock;
    @MockitoBean com.stockflow.common.storage.UploadInspection inspection;
    @MockitoBean com.stockflow.common.storage.FileTransfers files;
    @MockitoBean com.stockflow.identity.api.IdentityService identities;
    private final UUID owner = Identifiers.newId(), editor = Identifiers.newId(), reviewer = Identifiers.newId();

    private DesignWorkflowService.DraftView draftWithArtifact() throws Exception {
        when(identities.isActiveUserWithAnyRole(any(), any(String[].class))).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> {
            ((java.io.InputStream) call.getArgument(0)).readAllBytes();
            return null;
        }).when(inspection).requireClean(any());
        when(clock.instant()).thenReturn(Instant.parse("2026-09-17T00:00:00Z"));
        when(products.findById(any())).thenReturn(Optional.of(mock(ProductSummary.class)));
        var draft = workflow.create(Identifiers.newId(), Identifiers.newId(), owner, editor, reviewer,
                "Custom cabinet", "Width 1800 mm, white", editor);
        byte[] bytes = "confirmed bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact = artifacts.saveAndFlush(new DesignArtifactJpaEntity(Identifiers.newId(), draft.id(),
                new StoredFile("design-renders/test.pdf", "test.pdf", "application/pdf", bytes.length, Instant.EPOCH), checksum));
        var row = drafts.findById(draft.id()).orElseThrow();
        row.attachArtifact(artifact.getId());
        row.editedBy(editor);
        drafts.saveAndFlush(row);
        when(storage.download("design-renders/test.pdf")).thenAnswer(call -> new ByteArrayInputStream(bytes));
        return workflow.get(draft.id(), owner);
    }

    @Test void reviewCustomerConfirmationAndReplayPreserveExactSnapshot() throws Exception {
        var draft = draftWithArtifact();
        var reviewed = workflow.review(draft.id(), reviewer, draft.version(), draft.currentArtifactId(), true, "Dimensions and structure inspected");
        var sent = workflow.submit(draft.id(), editor, reviewed.version());
        var confirmed = workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId());
        var replay = workflow.confirm(draft.id(), owner, sent.version(), sent.currentArtifactId());
        assertThat(replay).isEqualTo(confirmed);
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

    @Test void orderVerificationRejectsTamperedStorageBytes() throws Exception {
        var draft = draftWithArtifact();
        var snapshot = confirm(draft);
        when(storage.download("design-renders/test.pdf")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThatThrownBy(() -> designs.verifySnapshot(snapshot.id(), draft.customerId()))
                .isInstanceOf(BusinessException.class);
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
