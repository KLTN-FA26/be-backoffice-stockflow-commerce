package com.stockflow;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.JpaAuditingConfig;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.product.internal.domain.ImageRendition;
import com.stockflow.product.internal.entity.MediaJpaEntity;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises Flyway plus JPA on PostgreSQL, including the deferred artifact FK. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class MediaPersistenceIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired EntityManager em;
    @MockitoBean CurrentUserProvider users;

    @Test void variantImageRoundTripsWithItsRenditions() {
        var product = ReferenceRows.product(em);
        var file = new StoredFile("product-images/test.png", "test.png", "image/png", 12, Instant.EPOCH);
        var rendition = new ImageRendition(320, 320, 240,
                new StoredFile("product-renditions/test-320.jpg", "display-320.jpg", "image/jpeg", 8, Instant.EPOCH));
        var id = Identifiers.newId();
        var image = new MediaJpaEntity(id, product.variantId(), file, List.of(rendition), "key-1", "a".repeat(64), 0);
        image.setPrimary(true);
        em.persist(image);
        em.flush();
        em.clear();
        var loaded = em.find(MediaJpaEntity.class, id);
        long version = loaded.getVersion();
        loaded.describe("Cup, front", 3);
        em.flush();
        em.clear();
        var updated = em.find(MediaJpaEntity.class, id);
        assertThat(updated.getVersion()).isGreaterThan(version);
        assertThat(updated.storedFile()).isEqualTo(file);
        assertThat(updated.getRenditions()).containsExactly(rendition);
        assertThat(updated.getAltText()).isEqualTo("Cup, front");
        assertThat(updated.isPrimary()).isTrue();
    }

    @Test void designReplacementKeepsPreviousRevisionAndInvalidatesPreflight() {
        var draftId = Identifiers.newId();
        // The customer, the people and (since C1) the product are foreign keys.
        var draft = new DesignDraftJpaEntity(draftId, ReferenceRows.customer(em), ReferenceRows.product(em).productId(),
                "Cup design", null, DesignStatus.DRAFT);
        em.persist(draft);
        var file = new StoredFile("design-renders/one.pdf", "one.pdf", "application/pdf", 12, Instant.EPOCH);
        var first = new DesignArtifactJpaEntity(Identifiers.newId(), draftId, file, "a".repeat(64));
        em.persist(first);
        draft.attachArtifact(first.getId());
        em.flush();
        var reviewer = ReferenceRows.user(em);
        draft.assign(ReferenceRows.user(em), ReferenceRows.user(em), reviewer);
        draft.review(reviewer, true, "Manual inspection completed");
        em.flush();
        em.refresh(draft);
        var next = new DesignArtifactJpaEntity(Identifiers.newId(), draftId,
                new StoredFile("design-renders/two.pdf", "two.pdf", "application/pdf", 14, Instant.EPOCH),
                "b".repeat(64));
        em.persist(next);
        draft.attachArtifact(next.getId());
        em.flush();
        em.createNativeQuery("set constraints all immediate").executeUpdate();
        assertThat(em.find(DesignArtifactJpaEntity.class, first.getId())).isNotNull();
        assertThat(draft.getCurrentArtifactId()).isEqualTo(next.getId());
        assertThat(em.createNativeQuery("select preflight_passed from design.design_draft where id = :id")
                .setParameter("id", draftId).getSingleResult()).isEqualTo(false);
    }
}
