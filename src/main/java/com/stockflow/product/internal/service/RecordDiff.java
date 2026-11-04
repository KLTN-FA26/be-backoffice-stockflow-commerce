package com.stockflow.product.internal.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which components of a record differ between two instances: the {@code changedFields} of a history
 * entry for a value that another module defines (a SKU's logistics, its stock policy).
 *
 * <p>Numbers compare by value, not by scale: {@code 8} and {@code 8.0} kilograms are the same weight.</p>
 */
final class RecordDiff {

    private RecordDiff() {
    }

    static List<String> changedFields(Record before, Record after) {
        if (before.getClass() != after.getClass()) {
            throw new IllegalArgumentException("Cannot compare " + before.getClass() + " with " + after.getClass());
        }
        List<String> changed = new ArrayList<>();
        for (RecordComponent component : before.getClass().getRecordComponents()) {
            if (!sameValue(read(component, before), read(component, after))) {
                changed.add(component.getName());
            }
        }
        return List.copyOf(changed);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Object read(RecordComponent component, Record record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of " + record.getClass(), e);
        }
    }
}
