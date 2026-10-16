package com.stockflow.product.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.domain.AttributeValue;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static com.stockflow.product.internal.domain.AttributeDataType.SELECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Attribute aggregate against Postgres: round trip, child merge, in-use checks, race translation. */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class AttributeRepositoryAdapterIntegrationTest {

    @Autowired AttributeRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private static AttributeDetails details() {
        return new AttributeDetails("Name", null, true, false, 0);
    }

    private static AttributeValueDetails value(String label, int sortOrder) {
        return new AttributeValueDetails(label, null, null, sortOrder);
    }

    private long valueRows(UUID attributeId) {
        return jdbc.queryForObject("select count(*) from product.attribute_values where attribute_id = ?",
                Long.class, attributeId);
    }

    @Test
    void anAttributeRoundTripsWithItsValues() {
        var a = Attribute.create(Identifiers.newId(), "CAPACITY", SELECT, details());
        a.addValue(Identifiers.newId(), "12OZ", new AttributeValueDetails("12 oz", new BigDecimal("12"), null, 1));
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        var loaded = repository.findById(a.id()).orElseThrow();

        assertThat(loaded.values()).extracting(AttributeValue::code).containsExactly("12OZ");
        assertThat(loaded.values().getFirst().details().valueNumber()).isEqualByComparingTo("12");
    }

    @Test
    void removingAValueFromTheAggregateDeletesItsRow() {
        var a = Attribute.create(Identifiers.newId(), "SIZE", SELECT, details());
        var keep = a.addValue(Identifiers.newId(), "S", value("Small", 1));
        var drop = a.addValue(Identifiers.newId(), "L", value("Large", 2));
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        var loaded = repository.findById(a.id()).orElseThrow();
        loaded.removeValue(drop.id(), 0L);
        repository.save(loaded);
        entityManager.clear();

        assertThat(valueRows(a.id())).isEqualTo(1);
        assertThat(repository.findById(a.id()).orElseThrow().values()).extracting(AttributeValue::id)
                .containsExactly(keep.id());
    }

    @Test
    void valuesAddedAndEditedOnAnExistingAttributeArePersisted() {
        var a = repository.save(Attribute.create(Identifiers.newId(), "FINISH", SELECT, details()));
        entityManager.flush();
        entityManager.clear();

        var loaded = repository.findById(a.id()).orElseThrow();
        var added = loaded.addValue(Identifiers.newId(), "MATTE", value("Matte", 1));
        loaded.update(loaded.version(), new AttributeDetails("Finish", "x", false, true, 3), false);
        var saved = repository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.version()).isEqualTo(1L);
        var reloaded = repository.findById(a.id()).orElseThrow();
        assertThat(reloaded.details().name()).isEqualTo("Finish");
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.values()).extracting(AttributeValue::id).containsExactly(added.id());

        reloaded.updateValue(added.id(), 0L, value("Matt", 2), false);
        repository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        var edited = repository.findById(a.id()).orElseThrow().values().getFirst();
        assertThat(edited.details().label()).isEqualTo("Matt");
        assertThat(edited.active()).isFalse();
        assertThat(edited.version()).isEqualTo(1L);
    }

    @Test
    void theDemoColourIsInUseAndAFreshAttributeIsNot() {
        assertThat(repository.isInUse(DemoData.demoId("demo:attr:COLOR"))).isTrue();
        assertThat(repository.isValueInUse(DemoData.demoId("demo:val:COLOR:GREY"))).isTrue();
        var fresh = repository.save(Attribute.create(Identifiers.newId(), "WALL", SELECT, details()));
        assertThat(repository.isInUse(fresh.id())).isFalse();
        assertThat(repository.existsByCode("WALL")).isTrue();
        assertThat(repository.existsByCode("NOPE")).isFalse();
    }

    @Test
    void aRacingDuplicateCodeIsTranslated() {
        repository.save(Attribute.create(Identifiers.newId(), "LID", SELECT, details()));
        assertThatThrownBy(() -> repository.save(Attribute.create(Identifiers.newId(), "LID", SELECT, details())))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS);
    }

    @Test
    void deletingAnAttributeInUseIsTranslated() {
        var colour = repository.findById(DemoData.demoId("demo:attr:COLOR")).orElseThrow();
        assertThatThrownBy(() -> repository.delete(colour))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
    }

    @Test
    void loadingAnAttributeWithValuesIsOneQuery() {
        var a = Attribute.create(Identifiers.newId(), "CUPSIZE", SELECT, details());
        a.addValue(Identifiers.newId(), "A", value("A", 1));
        a.addValue(Identifiers.newId(), "B", value("B", 2));
        a.addValue(Identifiers.newId(), "C", value("C", 3));
        repository.save(a);
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThat(repository.findById(a.id()).orElseThrow().values()).hasSize(3);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
