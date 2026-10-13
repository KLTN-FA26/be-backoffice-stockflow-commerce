package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.domain.AttributeValue;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.DescriptiveAttribute;
import com.stockflow.product.internal.domain.DescriptiveSpec;
import com.stockflow.product.internal.domain.DescriptiveValue;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductAttributeSet;
import com.stockflow.product.internal.domain.ProductAttributeSetRepository;
import com.stockflow.product.internal.domain.ProductAxis;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A product's variant axes and descriptive attributes (WBS 3.1.2). The rules live in
 * {@link ProductAttributeSet}; this service finds the pieces, runs the aggregate and shows the result.
 *
 * <p>Both writes take the product's row lock first. The set is stored as replaced rows, so two
 * overlapping replacements would otherwise each diff against the same committed rows and end in a
 * constraint or optimistic-lock error instead of one waiting for the other. The product row is the
 * natural lock because axes also depend on its status, which the lock keeps from changing underneath.</p>
 *
 * <p>An axis's options are shown in dictionary order (the value's sort order, then its code); the
 * order they were requested in is not stored.</p>
 *
 * <p>Every view, the read's and the writes', is built by {@link #view} from the aggregate plus the
 * dictionary: one path from storage to what the caller sees.</p>
 */
@Service
@Transactional
public class ProductAttributeService {

    private final ProductRepository products;
    private final ProductAttributeSetRepository sets;
    private final AttributeRepository attributes;

    public ProductAttributeService(ProductRepository products, ProductAttributeSetRepository sets,
                                   AttributeRepository attributes) {
        this.products = products;
        this.sets = sets;
        this.attributes = attributes;
    }

    public record ProductAttributesView(UUID productId, List<AxisView> axes, List<DescriptiveView> descriptives) {
    }

    public record AxisView(UUID attributeId, String code, String name, AttributeDataType dataType, int sortOrder,
                           List<OptionView> options) {
    }

    public record OptionView(UUID valueId, String code, String label, String hexColor, BigDecimal valueNumber,
                             boolean active, boolean usedByVariant) {
    }

    public record DescriptiveView(UUID attributeId, String code, String name, AttributeDataType dataType, String unit,
                                  int sortOrder, List<ValueRef> values, String text, BigDecimal number, Boolean bool) {
    }

    public record ValueRef(UUID valueId, String code, String label, boolean active) {
    }

    @Transactional(readOnly = true)
    public ProductAttributesView get(UUID productId) {
        Product product = products.findById(new ProductId(productId)).orElseThrow(() -> notFound(productId));
        ProductAttributeSet set = sets.load(productId, product.status());
        return view(set, dictionaryFor(set, Set.of()), sets.valueIdsUsedByVariants(productId));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-attributes", resourceId = "#productId")
    public ProductAttributesView replaceAxes(UUID productId, List<AxisSpec> axes) {
        Product product = lock(productId);
        ProductAttributeSet set = sets.load(productId, product.status());
        Map<UUID, Attribute> dictionary = dictionaryFor(set,
                axes.stream().map(AxisSpec::attributeId).collect(Collectors.toSet()));
        Set<UUID> used = sets.valueIdsUsedByVariants(productId);
        set.replaceAxes(axes, dictionary, used, Identifiers::newId);
        return view(sets.save(set), dictionary, used);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-attributes", resourceId = "#productId")
    public ProductAttributesView replaceDescriptives(UUID productId, List<DescriptiveSpec> descriptives) {
        Product product = lock(productId);
        ProductAttributeSet set = sets.load(productId, product.status());
        Map<UUID, Attribute> dictionary = dictionaryFor(set,
                descriptives.stream().map(DescriptiveSpec::attributeId).collect(Collectors.toSet()));
        set.replaceDescriptives(descriptives, dictionary, Identifiers::newId);
        return view(sets.save(set), dictionary, sets.valueIdsUsedByVariants(productId));
    }

    private Product lock(UUID productId) {
        return products.findForUpdate(new ProductId(productId)).orElseThrow(() -> notFound(productId));
    }

    /**
     * One query for the attributes the set holds plus those requested. An id that is not found is
     * left out of the map; the aggregate answers ATTRIBUTE_NOT_FOUND for a requested one, so that
     * message is written in one place.
     */
    private Map<UUID, Attribute> dictionaryFor(ProductAttributeSet set, Set<UUID> requested) {
        Set<UUID> ids = new HashSet<>(requested);
        set.axes().forEach(a -> ids.add(a.attributeId()));
        set.descriptives().forEach(d -> ids.add(d.attributeId()));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return attributes.findAllById(ids).stream().collect(Collectors.toMap(Attribute::id, Function.identity()));
    }

    private static BusinessException notFound(UUID productId) {
        return new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product " + productId);
    }

    private static ProductAttributesView view(ProductAttributeSet set, Map<UUID, Attribute> dictionary,
                                              Set<UUID> usedByVariants) {
        return new ProductAttributesView(set.productId(),
                set.axes().stream().map(a -> axisView(a, dictionary.get(a.attributeId()), usedByVariants)).toList(),
                set.descriptives().stream().map(d -> descriptiveView(d, dictionary.get(d.attributeId()))).toList());
    }

    private static AxisView axisView(ProductAxis axis, Attribute attribute, Set<UUID> usedByVariants) {
        Map<UUID, AttributeValue> values = byId(attribute);
        // The dictionary decides the order; the stored rows have none (see AxisSpec).
        List<OptionView> options = axis.options().stream().map(o -> values.get(o.valueId()))
                .sorted(Comparator.<AttributeValue>comparingInt(v -> v.details().sortOrder())
                        .thenComparing(AttributeValue::code))
                .map(v -> new OptionView(v.id(), v.code(), v.details().label(), v.details().hexColor(),
                        v.details().valueNumber(), v.active(), usedByVariants.contains(v.id())))
                .toList();
        return new AxisView(attribute.id(), attribute.code(), attribute.details().name(), attribute.dataType(),
                axis.sortOrder(), options);
    }

    private static DescriptiveView descriptiveView(DescriptiveAttribute d, Attribute attribute) {
        Map<UUID, AttributeValue> values = byId(attribute);
        List<ValueRef> refs = d.values().stream().filter(v -> v.valueId() != null).map(v -> {
            AttributeValue value = values.get(v.valueId());
            return new ValueRef(value.id(), value.code(), value.details().label(), value.active());
        }).toList();
        DescriptiveValue scalar = d.values().stream().filter(v -> v.valueId() == null).findFirst().orElse(null);
        return new DescriptiveView(attribute.id(), attribute.code(), attribute.details().name(), attribute.dataType(),
                attribute.details().unit(), d.sortOrder(), refs,
                scalar == null ? null : scalar.text(), scalar == null ? null : scalar.number(),
                scalar == null ? null : scalar.bool());
    }

    private static Map<UUID, AttributeValue> byId(Attribute attribute) {
        return attribute.values().stream().collect(Collectors.toMap(AttributeValue::id, Function.identity()));
    }
}
