package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.stockflow.product.internal.domain.AttributeDataType.BOOLEAN;
import static com.stockflow.product.internal.domain.AttributeDataType.COLOR;
import static com.stockflow.product.internal.domain.AttributeDataType.NUMBER;
import static com.stockflow.product.internal.domain.AttributeDataType.SELECT;
import static com.stockflow.product.internal.domain.AttributeDataType.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributeTest {

    @Test
    void codesAreNormalisedAndTheHyphenIsRefusedForAttributes() {
        assertThat(Attribute.create(id(), " cup_size ", SELECT, details()).code()).isEqualTo("CUP_SIZE");
        assertThatThrownBy(() -> Attribute.create(id(), "CUP-SIZE", SELECT, details()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        var select = Attribute.create(id(), "CAPACITY", SELECT, details());
        assertThat(select.addValue(id(), " 12oz-hot ", label("12 oz")).code()).isEqualTo("12OZ-HOT");
    }

    @Test
    void hexColourIsUpperCasedAndRequiredOnlyForColour() {
        var color = Attribute.create(id(), "COLOR", COLOR, details());
        assertThat(color.addValue(id(), "PINK", new AttributeValueDetails("Hồng", null, "#ff00aa", 0))
                .details().hexColor()).isEqualTo("#FF00AA");
        assertValidationFailed(() -> color.addValue(id(), "RED", new AttributeValueDetails("Đỏ", null, null, 0)));
        assertValidationFailed(() -> color.addValue(id(), "BAD", new AttributeValueDetails("x", null, "#GG0000", 0)));
        var select = Attribute.create(id(), "CAPACITY", SELECT, details());
        assertValidationFailed(() -> select.addValue(id(), "12OZ", new AttributeValueDetails("12 oz", null, "#000000", 0)));
    }

    @Test
    void onlySelectMultiselectAndColourHoldValues() {
        for (var type : List.of(TEXT, NUMBER, BOOLEAN)) {
            assertValidationFailed(() -> Attribute.create(id(), "X", type, details()).addValue(id(), "A", label("a")));
        }
    }

    @Test
    void aValueCodeIsUniqueWithinItsAttribute() {
        var a = Attribute.create(id(), "CAPACITY", SELECT, details());
        a.addValue(id(), "12OZ", label("12 oz"));
        assertThatThrownBy(() -> a.addValue(id(), "12oz", label("again")))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS);
    }

    @Test
    void aStaleVersionIsRefused() {
        var a = new Attribute(id(), "CAPACITY", SELECT, details(), true, 3, List.of());
        assertThatThrownBy(() -> a.update(2, details(), true))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThatThrownBy(() -> a.requireVersion(2))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        var value = new AttributeValue(id(), "12OZ", label("12 oz"), true, 5);
        var b = new Attribute(id(), "CAPACITY", SELECT, details(), true, 0, List.of(value));
        assertThatThrownBy(() -> b.updateValue(value.id(), 4, label("x"), true))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void aStaleValueVersionIsRefusedOnRemoval() {
        var value = new AttributeValue(id(), "12OZ", label("12 oz"), true, 5);
        var a = new Attribute(id(), "CAPACITY", SELECT, details(), true, 0, List.of(value));
        assertThatThrownBy(() -> a.removeValue(value.id(), 4))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(a.values()).hasSize(1);
        a.removeValue(value.id(), 5);
        assertThat(a.values()).isEmpty();
    }

    @Test
    void detailsValidateNameLabelAndUnit() {
        assertValidationFailed(() -> new AttributeDetails(" ", null, false, false, 0));
        assertValidationFailed(() -> new AttributeDetails("Dung tích", "x".repeat(21), false, false, 0));
        assertThat(new AttributeDetails(" Dung tích ", " ", false, false, 0).unit()).isNull();
        assertValidationFailed(() -> new AttributeValueDetails("", null, null, 0));
    }

    @Test
    void valuesAreOrderedBySortOrderThenCode() {
        var a = Attribute.create(id(), "CAPACITY", SELECT, details());
        a.addValue(id(), "22OZ", new AttributeValueDetails("22", null, null, 2));
        a.addValue(id(), "16OZ", new AttributeValueDetails("16", null, null, 1));
        a.addValue(id(), "12OZ", new AttributeValueDetails("12", null, null, 1));
        assertThat(a.values()).extracting(AttributeValue::code).containsExactly("12OZ", "16OZ", "22OZ");
    }

    @Test
    void removingAnUnknownValueIsNotFound() {
        var a = Attribute.create(id(), "CAPACITY", SELECT, details());
        assertThatThrownBy(() -> a.removeValue(id(), 0))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ATTRIBUTE_VALUE_NOT_FOUND);
    }

    private static UUID id() {
        return Identifiers.newId();
    }

    private static AttributeDetails details() {
        return new AttributeDetails("Dung tích", null, true, false, 0);
    }

    private static AttributeValueDetails label(String s) {
        return new AttributeValueDetails(s, null, null, 0);
    }

    private static void assertValidationFailed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
