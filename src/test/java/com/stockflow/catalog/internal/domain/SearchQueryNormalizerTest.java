package com.stockflow.catalog.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

class SearchQueryNormalizerTest {

    @Test
    void foldsVietnameseDiacriticsAndLowerCases() {
        var q = SearchQueryNormalizer.normalize("Bàn Ăn Gỗ Sồi, Đà Nẵng");
        assertThat(q.text()).isEqualTo("ban an go soi da nang");
        assertThat(q.terms()).containsExactly("ban", "an", "go", "soi", "da", "nang");
    }

    @Test
    void decomposedInputBehavesLikePrecomposedInput() {
        String nfd = Normalizer.normalize("Bàn gỗ", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("Bàn gỗ");
        assertThat(SearchQueryNormalizer.normalize(nfd)).isEqualTo(SearchQueryNormalizer.normalize("Bàn gỗ"));
        assertThat(SearchQueryNormalizer.normalize(nfd).text()).isEqualTo("ban go");
    }

    @Test
    void collapsesWhitespaceAndStripsEveryTsqueryOperator() {
        var q = SearchQueryNormalizer.normalize("  ly   nhựa  & | ! ( ) : * ' \\ < > ' ");
        assertThat(q.terms()).containsExactly("ly", "nhua");
        assertThat(q.text()).matches("[\\p{L}\\p{N} ]+");
    }

    @Test
    void keepsDigitsAndSplitsOnPunctuation() {
        assertThat(SearchQueryNormalizer.normalize("Ly 12oz-500ml").terms())
                .containsExactly("ly", "12oz", "500ml");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "!!!", "&|:*()", "'\"\\"})
    void nothingSearchableIsEmptyNotAnError(String raw) {
        var q = SearchQueryNormalizer.normalize(raw);
        assertThat(q.isEmpty()).isTrue();
        assertThat(q.skuKeys()).isEmpty();
    }

    @Test
    void lastTermIsThePrefixAndHeadIsTheRest() {
        var q = SearchQueryNormalizer.normalize("ly nhu");
        assertThat(q.lastTerm()).isEqualTo("nhu");
        assertThat(q.head()).isEqualTo("ly");
        assertThat(SearchQueryNormalizer.normalize("ly").head()).isEmpty();
    }

    @Test
    void skuKeysCoverHyphenatedAndSpacedForms() {
        assertThat(SearchQueryNormalizer.normalize("SOFA-3S-GREY").skuKeys()).containsExactly("sofa-3s-grey");
        assertThat(SearchQueryNormalizer.normalize("sofa 3s grey").skuKeys()).containsExactly("sofa-3s-grey");
        assertThat(SearchQueryNormalizer.normalize("sofa_3s").skuKeys()).containsExactly("sofa_3s", "sofa-3s");
    }

    @Test
    void anAccentedWordIsNeverAnSkuKeyOnItsOwnAccents() {
        // "Bàn" folds to "ban"; the SKU key is the folded form, ASCII only.
        assertThat(SearchQueryNormalizer.normalize("Bàn").skuKeys()).containsExactly("ban");
    }

    @Test
    void exactlyTheMaximumLengthIsAccepted() {
        assertThat(SearchQueryNormalizer.normalize("a".repeat(SearchQueryNormalizer.MAX_LENGTH)).isEmpty())
                .isFalse();
    }

    @Test
    void oneCharacterOverTheMaximumIsRefusedWithoutEchoingTheInput() {
        String secret = "0901234567" + "x".repeat(SearchQueryNormalizer.MAX_LENGTH);
        assertThatThrownBy(() -> SearchQueryNormalizer.normalize(secret))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).doesNotContain("0901234567");
                    assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                        assertThat(f.field()).isEqualTo("q");
                        assertThat(f.message()).doesNotContain("0901234567");
                    });
                });
    }

    @Test
    void lengthIsMeasuredAfterTrimmingAndInCodePoints() {
        assertThat(SearchQueryNormalizer.normalize("  " + "a".repeat(100) + "  ").terms()).hasSize(1);
        // 100 supplementary characters (2 UTF-16 units each) are 100 code points, not 200.
        assertThat(SearchQueryNormalizer.normalize("😀".repeat(100)).isEmpty()).isTrue();
    }

    @Test
    void foldMatchesTheDatabaseFoldForTheVietnameseAlphabet() {
        String accented = "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ";
        String plain = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd";
        assertThat(SearchQueryNormalizer.fold(accented)).isEqualTo(plain);
        assertThat(SearchQueryNormalizer.fold(accented.toUpperCase())).isEqualTo(plain);
    }
}
