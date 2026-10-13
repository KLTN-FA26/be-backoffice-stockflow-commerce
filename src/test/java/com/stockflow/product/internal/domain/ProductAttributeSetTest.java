package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.ProductStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.stockflow.common.error.ErrorCode.ATTRIBUTE_IN_USE;
import static com.stockflow.common.error.ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION;
import static com.stockflow.common.error.ErrorCode.PRODUCT_AXES_LOCKED;
import static com.stockflow.common.error.ErrorCode.VALIDATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAttributeSetTest {

    private static final AttributeDetails DETAILS = new AttributeDetails("x", null, false, false, 0);

    private final Attribute CAPACITY = attribute("CAPACITY", AttributeDataType.SELECT);
    private final Attribute COLOR = attribute("COLOR", AttributeDataType.COLOR);
    private final Attribute MATERIAL = attribute("MATERIAL", AttributeDataType.SELECT);
    private final Attribute FEATURES = attribute("FEATURES", AttributeDataType.MULTISELECT);
    private final Attribute NOTE = attribute("NOTE", AttributeDataType.TEXT);
    private final Attribute WEIGHT = attribute("WEIGHT", AttributeDataType.NUMBER);
    private final Attribute RECYCLABLE = attribute("RECYCLABLE", AttributeDataType.BOOLEAN);

    private final UUID OZ12 = value(CAPACITY, "12OZ");
    private final UUID OZ16 = value(CAPACITY, "16OZ");
    private final UUID CLEAR = colorValue(COLOR, "CLEAR");
    private final UUID PET = value(MATERIAL, "PET");
    private final UUID FABRIC = value(MATERIAL, "FABRIC");
    private final UUID OAK = value(MATERIAL, "OAK");
    private final UUID LID = value(FEATURES, "LID");
    private final UUID STRAW = value(FEATURES, "STRAW");

    @Test
    void aDraftProductGetsItsAxesAndOptions() {
        var set = draft();
        set.replaceAxes(List.of(new AxisSpec(CAPACITY.id(), 0, List.of(OZ12, OZ16)),
                new AxisSpec(COLOR.id(), 1, List.of(CLEAR))), dictionary(), Set.of(), ids());
        assertThat(set.axes()).extracting(ProductAxis::attributeId).containsExactly(CAPACITY.id(), COLOR.id());
        assertThat(set.axes().getFirst().options()).extracting(AxisOption::valueId).containsExactlyInAnyOrder(OZ12, OZ16);
    }

    @Test
    void axesAreOrderedBySortOrder() {
        var set = draft();
        set.replaceAxes(List.of(new AxisSpec(CAPACITY.id(), 2, List.of(OZ12)),
                new AxisSpec(COLOR.id(), 1, List.of(CLEAR))), dictionary(), Set.of(), ids());
        assertThat(set.axes()).extracting(ProductAxis::attributeId).containsExactly(COLOR.id(), CAPACITY.id());
    }

    @Test
    void addingOrRemovingAnAxisIsLockedOnceTheProductLeftDraft() {
        var set = approved(axis(CAPACITY, OZ12));
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12), spec(COLOR, CLEAR)), dictionary(), Set.of(), ids()),
                PRODUCT_AXES_LOCKED);
        assertCode(() -> set.replaceAxes(List.of(), dictionary(), Set.of(), ids()), PRODUCT_AXES_LOCKED);
    }

    @Test
    void noAxisCanBeAddedOnceVariantsUseOptions() {
        var set = draft(axis(CAPACITY, OZ12));
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12), spec(COLOR, CLEAR)), dictionary(), Set.of(OZ12), ids()),
                PRODUCT_AXES_LOCKED);
        assertThat(set.axes()).hasSize(1);
        set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ16)), dictionary(), Set.of(OZ12), ids());
        assertThat(set.axes().getFirst().options()).hasSize(2);
        // with no variant using an option, a draft may still gain an axis
        set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ16), spec(COLOR, CLEAR)), dictionary(), Set.of(), ids());
        assertThat(set.axes()).hasSize(2);
    }

    @Test
    void optionForFindsTheOptionOfAnAxis() {
        var set = draft(axis(CAPACITY, OZ12), axis(COLOR, CLEAR));
        assertThat(set.optionFor(CAPACITY.id(), OZ12)).get().extracting(AxisOption::valueId).isEqualTo(OZ12);
        assertThat(set.optionFor(CAPACITY.id(), OZ16)).isEmpty();
        assertThat(set.optionFor(CAPACITY.id(), CLEAR)).isEmpty();
        assertThat(set.optionFor(MATERIAL.id(), PET)).isEmpty();
    }

    @Test
    void anApprovedProductMayGainAnOptionAndReorder() {
        var set = approved(axis(CAPACITY, OZ12));
        set.replaceAxes(List.of(new AxisSpec(CAPACITY.id(), 5, List.of(OZ12, OZ16))), dictionary(), Set.of(), ids());
        assertThat(set.axes().getFirst().options()).extracting(AxisOption::valueId).containsExactly(OZ12, OZ16);
        assertThat(set.axes().getFirst().sortOrder()).isEqualTo(5);
    }

    @Test
    void aPendingOrDiscontinuedProductAcceptsNoChange() {
        for (var status : List.of(ProductStatus.PENDING_APPROVAL, ProductStatus.DISCONTINUED)) {
            var set = new ProductAttributeSet(Identifiers.newId(), status, List.of(axis(CAPACITY, OZ12)), List.of());
            assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12)), dictionary(), Set.of(), ids()),
                    INVALID_PRODUCT_STATUS_TRANSITION);
            assertCode(() -> set.replaceDescriptives(List.of(), dictionary(), ids()), INVALID_PRODUCT_STATUS_TRANSITION);
        }
    }

    @Test
    void theInUseMessageNamesTheValueByCode() {
        var set = draft(axis(CAPACITY, OZ12, OZ16));
        assertThatThrownBy(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ16)), dictionary(), Set.of(OZ12), ids()))
                .hasMessageContaining("12OZ").hasMessageNotContaining(OZ12.toString());
    }

    @Test
    void removingAWholeAxisWhoseOptionAVariantUsesIsRefused() {
        var set = draft(axis(CAPACITY, OZ12));
        assertCode(() -> set.replaceAxes(List.of(), dictionary(), Set.of(OZ12), ids()), ATTRIBUTE_IN_USE);
        assertThat(set.axes()).singleElement().satisfies(a ->
                assertThat(a.options()).extracting(AxisOption::valueId).containsExactly(OZ12));
    }

    @Test
    void removingAnOptionAVariantUsesIsRefused() {
        var set = draft(axis(CAPACITY, OZ12, OZ16));
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ16)), dictionary(), Set.of(OZ12), ids()), ATTRIBUTE_IN_USE);
        assertThat(set.axes().getFirst().options()).hasSize(2);
    }

    @Test
    void removingAnOptionNoVariantUsesIsAllowed() {
        var set = draft(axis(CAPACITY, OZ12, OZ16));
        set.replaceAxes(List.of(spec(CAPACITY, OZ16)), dictionary(), Set.of(OZ16), ids());
        assertThat(set.axes().getFirst().options()).extracting(AxisOption::valueId).containsExactly(OZ16);
    }

    @Test
    void axisRules() {
        var d = dictionary();
        var set = draft();
        assertCode(() -> set.replaceAxes(List.of(spec(NOTE)), d, Set.of(), ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceAxes(List.of(spec(FEATURES, LID)), d, Set.of(), ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY)), d, Set.of(), ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12), spec(CAPACITY, OZ16)), d, Set.of(), ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ12)), d, Set.of(), ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, PET)), d, Set.of(), ids()), VALIDATION_FAILED);

        var inactiveAttribute = new Attribute(COLOR.id(), "COLOR", AttributeDataType.COLOR, DETAILS, false, 0, COLOR.values());
        assertCode(() -> set.replaceAxes(List.of(spec(COLOR, CLEAR)), Map.of(COLOR.id(), inactiveAttribute), Set.of(), ids()),
                VALIDATION_FAILED);
        var inactiveValue = withInactiveValue(CAPACITY, OZ16);
        assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ16)), Map.of(CAPACITY.id(), inactiveValue), Set.of(), ids()),
                VALIDATION_FAILED);
    }

    @Test
    void anInactiveAttributeOrValueMayStay() {
        var set = draft(axis(CAPACITY, OZ12));
        var inactive = new Attribute(CAPACITY.id(), "CAPACITY", AttributeDataType.SELECT, DETAILS, false, 0,
                withInactiveValue(CAPACITY, OZ12).values());
        assertThatCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12)), Map.of(CAPACITY.id(), inactive), Set.of(), ids()))
                .doesNotThrowAnyException();
    }

    @Test
    void anAttributeCannotBeInBothRoles() {
        var set = draft();
        set.replaceDescriptives(List.of(new DescriptiveSpec(MATERIAL.id(), 0, List.of(PET), null, null, null)), dictionary(), ids());
        assertThatThrownBy(() -> set.replaceAxes(List.of(spec(MATERIAL, PET)), dictionary(), Set.of(), ids()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("MATERIAL");
                });
        var other = draft(axis(CAPACITY, OZ12));
        assertCode(() -> other.replaceDescriptives(
                List.of(new DescriptiveSpec(CAPACITY.id(), 0, List.of(OZ12), null, null, null)), dictionary(), ids()),
                VALIDATION_FAILED);
    }

    @Test
    void descriptiveValuesFollowTheType() {
        var d = dictionary();
        var set = draft();
        // SELECT / COLOR: exactly one value
        set.replaceDescriptives(List.of(sel(MATERIAL, PET)), d, ids());
        assertThat(set.descriptives().getFirst().values()).hasSize(1);
        assertCode(() -> set.replaceDescriptives(List.of(sel(MATERIAL, PET, FABRIC)), d, ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(sel(MATERIAL)), d, ids()), VALIDATION_FAILED);
        set.replaceDescriptives(List.of(sel(COLOR, CLEAR)), d, ids());
        // MULTISELECT: one or more
        set.replaceDescriptives(List.of(sel(FEATURES, LID, STRAW)), d, ids());
        assertThat(set.descriptives().getFirst().values()).hasSize(2);
        assertCode(() -> set.replaceDescriptives(List.of(sel(FEATURES)), d, ids()), VALIDATION_FAILED);
        // a value must belong to the attribute
        assertCode(() -> set.replaceDescriptives(List.of(sel(MATERIAL, LID)), d, ids()), VALIDATION_FAILED);
        // TEXT
        set.replaceDescriptives(List.of(new DescriptiveSpec(NOTE.id(), 0, List.of(), "hello", null, null)), d, ids());
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(NOTE.id(), 0, List.of(), "  ", null, null)), d, ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(NOTE.id(), 0, List.of(), null, null, null)), d, ids()),
                VALIDATION_FAILED);
        // NUMBER
        set.replaceDescriptives(List.of(new DescriptiveSpec(WEIGHT.id(), 0, List.of(), null, BigDecimal.TEN, null)), d, ids());
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(WEIGHT.id(), 0, List.of(), null, null, null)), d, ids()),
                VALIDATION_FAILED);
        // BOOLEAN
        set.replaceDescriptives(List.of(new DescriptiveSpec(RECYCLABLE.id(), 0, List.of(), null, null, true)), d, ids());
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(RECYCLABLE.id(), 0, List.of(PET), null, null, true)), d, ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(RECYCLABLE.id(), 0, List.of(), null, null, null)), d, ids()),
                VALIDATION_FAILED);
        // a foreign field on any type
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(MATERIAL.id(), 0, List.of(PET), "t", null, null)), d, ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(NOTE.id(), 0, List.of(), "t", BigDecimal.ONE, null)), d, ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(WEIGHT.id(), 0, List.of(), "t", BigDecimal.ONE, null)), d, ids()),
                VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(new DescriptiveSpec(RECYCLABLE.id(), 0, List.of(), null, BigDecimal.ONE, true)), d, ids()),
                VALIDATION_FAILED);
    }

    @Test
    void descriptiveRulesAboutAttributeIdentity() {
        var d = dictionary();
        var set = draft();
        assertCode(() -> set.replaceDescriptives(List.of(sel(MATERIAL, PET), sel(MATERIAL, FABRIC)), d, ids()), VALIDATION_FAILED);
        assertCode(() -> set.replaceDescriptives(List.of(sel(attribute("GHOST", AttributeDataType.SELECT))), d, ids()),
                ErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void keptEntriesKeepTheirIds() {
        var set = draft(axis(CAPACITY, OZ12));
        UUID axisId = set.axes().getFirst().id();
        UUID optionId = set.axes().getFirst().options().getFirst().id();
        set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ16)), dictionary(), Set.of(), ids());
        assertThat(set.axes().getFirst().id()).isEqualTo(axisId);
        assertThat(set.axes().getFirst().options().getFirst().id()).isEqualTo(optionId);
        assertThat(set.axes().getFirst().options().getLast().id()).isNotIn(axisId, optionId);

        var withDescriptives = new ProductAttributeSet(Identifiers.newId(), ProductStatus.DRAFT, List.of(), List.of(
                new DescriptiveAttribute(Identifiers.newId(), MATERIAL.id(), 0,
                        List.of(new DescriptiveValue(Identifiers.newId(), FABRIC, null, null, null))),
                new DescriptiveAttribute(Identifiers.newId(), NOTE.id(), 1,
                        List.of(new DescriptiveValue(Identifiers.newId(), null, "old", null, null))),
                new DescriptiveAttribute(Identifiers.newId(), FEATURES.id(), 2,
                        List.of(new DescriptiveValue(Identifiers.newId(), LID, null, null, null)))));
        UUID selectRow = withDescriptives.descriptives().get(0).values().getFirst().id();
        UUID textRow = withDescriptives.descriptives().get(1).values().getFirst().id();
        UUID lidRow = withDescriptives.descriptives().get(2).values().getFirst().id();
        UUID materialId = withDescriptives.descriptives().get(0).id();
        withDescriptives.replaceDescriptives(List.of(
                sel(MATERIAL, OAK),
                new DescriptiveSpec(NOTE.id(), 1, List.of(), "new", null, null),
                new DescriptiveSpec(FEATURES.id(), 2, List.of(LID, STRAW), null, null, null)), dictionary(), ids());
        assertThat(withDescriptives.descriptives().get(0).id()).isEqualTo(materialId);
        assertThat(withDescriptives.descriptives().get(0).values().getFirst().id()).isEqualTo(selectRow);
        assertThat(withDescriptives.descriptives().get(0).values().getFirst().valueId()).isEqualTo(OAK);
        assertThat(withDescriptives.descriptives().get(1).values().getFirst().id()).isEqualTo(textRow);
        assertThat(withDescriptives.descriptives().get(1).values().getFirst().text()).isEqualTo("new");
        assertThat(withDescriptives.descriptives().get(2).values().getFirst().id()).isEqualTo(lidRow);
        assertThat(withDescriptives.descriptives().get(2).values()).hasSize(2);
    }

    // ---- fixtures ----

    private ProductAttributeSet draft(ProductAxis... axes) {
        return new ProductAttributeSet(Identifiers.newId(), ProductStatus.DRAFT, List.of(axes), List.of());
    }

    private ProductAttributeSet approved(ProductAxis... axes) {
        return new ProductAttributeSet(Identifiers.newId(), ProductStatus.APPROVED, List.of(axes), List.of());
    }

    private static ProductAxis axis(Attribute attribute, UUID... valueIds) {
        List<AxisOption> options = new ArrayList<>();
        for (UUID v : valueIds) {
            options.add(new AxisOption(Identifiers.newId(), v));
        }
        return new ProductAxis(Identifiers.newId(), attribute.id(), 0, options);
    }

    private static AxisSpec spec(Attribute attribute, UUID... valueIds) {
        return new AxisSpec(attribute.id(), 0, List.of(valueIds));
    }

    private static DescriptiveSpec sel(Attribute attribute, UUID... valueIds) {
        return new DescriptiveSpec(attribute.id(), 0, List.of(valueIds), null, null, null);
    }

    private Map<UUID, Attribute> dictionary() {
        Map<UUID, Attribute> map = new HashMap<>();
        for (Attribute a : List.of(CAPACITY, COLOR, MATERIAL, FEATURES, NOTE, WEIGHT, RECYCLABLE)) {
            map.put(a.id(), a);
        }
        return map;
    }

    private static Supplier<UUID> ids() {
        return Identifiers::newId;
    }

    private static Attribute attribute(String code, AttributeDataType type) {
        return Attribute.create(Identifiers.newId(), code, type, DETAILS);
    }

    private static UUID value(Attribute attribute, String code) {
        return attribute.addValue(Identifiers.newId(), code, new AttributeValueDetails(code, null, null, 0)).id();
    }

    private static UUID colorValue(Attribute attribute, String code) {
        return attribute.addValue(Identifiers.newId(), code, new AttributeValueDetails(code, null, "#FFFFFF", 0)).id();
    }

    /** A copy of the attribute whose given value is deactivated. */
    private static Attribute withInactiveValue(Attribute source, UUID valueId) {
        List<AttributeValue> values = source.values().stream()
                .map(v -> v.id().equals(valueId)
                        ? new AttributeValue(v.id(), v.code(), v.details(), false, 0)
                        : v)
                .toList();
        return new Attribute(source.id(), source.code(), source.dataType(), source.details(), true, 0, values);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
