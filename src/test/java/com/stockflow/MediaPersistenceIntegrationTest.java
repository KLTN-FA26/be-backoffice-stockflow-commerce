package com.stockflow;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.JpaAuditingConfig;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.entity.ProductImageJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
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

    @Test void productImageRoundTripsAndRepeatedSaveRetainsManagedChildren() {
        var id = Identifiers.newId();
        var product = new ProductJpaEntity(id, "MEDIA-TEST", "Cup", "Cup", null, null, null,
                "StockFlow", TaxClass.STANDARD, ProductStatus.DRAFT, true,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, false, null);
        var file = new StoredFile("product-images/test.png", "test.png", "image/png", 12, Instant.EPOCH);
        var imageId = Identifiers.newId();
        product.replaceImages(List.of(new ProductImageJpaEntity(imageId, null, 0, file)));
        em.persist(product);
        em.flush();
        em.clear();
        var loaded = em.find(ProductJpaEntity.class, id);
        long version = loaded.getVersion();
        loaded.replaceImages(List.of(new ProductImageJpaEntity(imageId, null, 0, file)));
        em.flush();
        em.clear();
        var updated = em.find(ProductJpaEntity.class, id);
        assertThat(updated.getVersion()).isGreaterThan(version);
        assertThat(updated.getImages()).hasSize(1);
        assertThat(updated.getImages().getFirst().storedFile()).isEqualTo(file);
    }

    @Test void designReplacementKeepsPreviousRevisionAndInvalidatesPreflight() {
        var draftId = Identifiers.newId();
        var draft = new DesignDraftJpaEntity(draftId, Identifiers.newId(), Identifiers.newId(),
                "Cup design", null, DesignStatus.DRAFT);
        em.persist(draft);
        var file = new StoredFile("design-renders/one.pdf", "one.pdf", "application/pdf", 12, Instant.EPOCH);
        var first = new DesignArtifactJpaEntity(Identifiers.newId(), draftId, file, "a".repeat(64));
        em.persist(first);
        draft.attachArtifact(first.getId());
        em.flush();
        var reviewer = Identifiers.newId();
        draft.assign(Identifiers.newId(), Identifiers.newId(), reviewer);
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
