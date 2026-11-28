package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeParser;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The error explanations' map ({@code assistant/error-rules.yml}) against the two things it names: {@link ErrorCode}
 * and the knowledge corpus as the build indexes it ({@code docs/kltn-snapshot}, parsed as {@link KnowledgeService}
 * parses it). A rule renamed or dropped by a kltn-docs sync, or an error code renamed, fails here and not in front of a
 * refused user.
 */
class ErrorRulesTest {

    /** Every rule anchor of the corpus the build puts on the classpath. */
    private static List<KnowledgeChunk> corpus() {
        return KnowledgeService.documents().stream()
                .filter(document -> !document.name().equals(KnowledgeParser.GLOSSARY))
                .flatMap(document -> KnowledgeParser.chapter(document.name(), document.markdown()).stream())
                .filter(chunk -> chunk.kind() == KnowledgeChunk.Kind.RULE)
                .toList();
    }

    @Test
    @DisplayName("every mapped code is an ErrorCode, every mapped rule is a rule of the indexed corpus, and the map "
            + "covers the refusals people meet in daily work")
    void theMapNamesOnlyWhatExists() {
        Map<ErrorCode, ErrorExplanations.Mapping> map = ErrorExplanations.load();
        Set<String> anchors = corpus().stream().map(KnowledgeChunk::anchor).collect(Collectors.toSet());

        assertThat(map).hasSizeBetween(25, 60);
        map.forEach((code, mapping) -> {
            assertThat(mapping.rules()).as(code + " rules").allSatisfy(rule ->
                    assertThat(anchors).as(code + " names " + rule).contains(rule));
            assertThat(mapping.nextSteps()).as(code + " next steps").isNotEmpty().hasSizeLessThanOrEqualTo(3);
        });
        assertThat(map.keySet()).contains(ErrorCode.LOAD_HANDOVER_BLOCKED, ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING,
                ErrorCode.PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING, ErrorCode.INSUFFICIENT_STOCK,
                ErrorCode.CYCLE_COUNT_SLOT_BUSY, ErrorCode.QUOTE_PRICE_REVIEW_PENDING,
                ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("a code that is no ErrorCode, a rule not shaped like an anchor, or no next step fails the load")
    void aWrongEntryFailsTheLoad() {
        assertThatThrownBy(() -> parse("""
                errors:
                  NOT_A_CODE:
                    rules: []
                    nextSteps: ["x"]
                """)).hasMessageContaining("NOT_A_CODE is not an ErrorCode");
        assertThatThrownBy(() -> parse("""
                errors:
                  QUOTE_EXPIRED:
                    rules: ["BR-08"]
                    nextSteps: ["x"]
                """)).hasMessageContaining("not a rule as the corpus anchors it");
        assertThatThrownBy(() -> parse("""
                errors:
                  QUOTE_EXPIRED:
                    rules: ["14 BR-06"]
                    nextSteps: []
                """)).hasMessageContaining("one to 3");
        assertThatThrownBy(() -> parse("""
                errors:
                  QUOTE_EXPIRED:
                    rules: ["14 BR-06"]
                    nextSteps: ["a", "b", "c", "d"]
                """)).hasMessageContaining("one to 3");
    }

    @Test
    @DisplayName("an explanation: the message in the caller's language, each mapped rule whole from the index, the "
            + "next steps; a rule cut into passages comes back as one text, in order")
    void explainsAMappedCode() {
        InMemoryKnowledgeRepository knowledge = new InMemoryKnowledgeRepository();
        Map<String, List<KnowledgeChunk>> bySource = corpus().stream()
                .collect(Collectors.groupingBy(KnowledgeChunk::source));
        bySource.forEach((source, chunks) -> knowledge.sync(source, chunks, null));
        // A long rule as the parser cuts it: the parts must come back in their order, (10) after (2).
        knowledge.sync("99-test", List.of(
                new KnowledgeChunk("99-test", "99 BR-01", KnowledgeChunk.Kind.RULE, "99 BR-01 Dài (1/3)", "một"),
                new KnowledgeChunk("99-test", "99 BR-01 (10)", KnowledgeChunk.Kind.RULE, "99 BR-01 Dài (3/3)", "ba"),
                new KnowledgeChunk("99-test", "99 BR-01 (2)", KnowledgeChunk.Kind.RULE, "99 BR-01 Dài (2/3)", "hai"),
                new KnowledgeChunk("99-test", "99 BR-011", KnowledgeChunk.Kind.RULE, "99 BR-011", "khác")), null);
        ErrorExplanations explanations = new ErrorExplanations(Map.of(
                ErrorCode.LOAD_HANDOVER_BLOCKED, new ErrorExplanations.Mapping(List.of("09 BR-18", "15 BR-08"),
                        List.of("Bước một.")),
                ErrorCode.QUOTE_EXPIRED, new ErrorExplanations.Mapping(List.of("99 BR-01", "98 BR-01"),
                        List.of("Bước."))),
                knowledge, bundles(), mock(PlatformTransactionManager.class));

        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        try {
            ErrorExplanations.Explanation handover = explanations.explain("LOAD_HANDOVER_BLOCKED");
            assertThat(handover.message()).isEqualTo("Có đơn hàng trên xe chưa được phép bàn giao");
            assertThat(handover.rules()).extracting(ErrorExplanations.Rule::ruleId)
                    .containsExactly("09 BR-18", "15 BR-08");
            ErrorExplanations.Rule payment = handover.rules().get(1);
            assertThat(payment.chapter()).isEqualTo("15-payment");
            assertThat(payment.title()).startsWith("15 BR-08");
            assertThat(payment.text()).contains("BEFORE_DELIVERY");
            assertThat(handover.nextSteps()).containsExactly("Bước một.");

            ErrorExplanations.Explanation cut = explanations.explain("QUOTE_EXPIRED");
            assertThat(cut.rules()).as("a rule the index does not hold is left out, not shown empty")
                    .singleElement().satisfies(rule -> {
                        assertThat(rule.text()).isEqualTo("một\nhai\nba");
                        assertThat(rule.title()).isEqualTo("99 BR-01 Dài");
                    });
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("a code the map does not hold is explained by its message alone, in English for an English reader; "
            + "a value that is no code is not found")
    void unmappedAndUnknown() {
        ErrorExplanations explanations = new ErrorExplanations(Map.of(), new InMemoryKnowledgeRepository(), bundles(),
                mock(PlatformTransactionManager.class));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            ErrorExplanations.Explanation plain = explanations.explain("QUOTE_EXPIRED");
            assertThat(plain.message()).isEqualTo("This quote is no longer valid");
            assertThat(plain.rules()).isEmpty();
            assertThat(plain.nextSteps()).isEmpty();
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        assertThatThrownBy(() -> explanations.explain("DROP TABLE")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> explanations.explain("quote_expired")).isInstanceOf(NotFoundException.class);
    }

    private static Map<ErrorCode, ErrorExplanations.Mapping> parse(String yaml) {
        return ErrorExplanations.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** The application's own bundles, read as {@code I18nConfig} reads them. */
    private static Messages bundles() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }
}
