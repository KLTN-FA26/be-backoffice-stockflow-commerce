package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.util.Currency;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Attach conditional validation failures to the actual editable field, not a synthetic getter. */
public class ProcurementFieldsValidator implements ConstraintValidator<ValidProcurementFields, Object> {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (value instanceof SaveSupplierRequest supplier) {
            if (!supplier.isDeliveryContactValid()) {
                field(context, "EMAIL".equals(supplier.communicationChannel()) ? "email" : "apiEndpoint",
                        "{procurement.deliveryContact}");
                valid = false;
            }
            if (!supplier.isPhoneDigitsValid()) {
                field(context, "phone", "{procurement.phoneDigits}");
                valid = false;
            }
        }
        if (value instanceof SupplierConfirmationRequest response && !response.isRejectionReasonPresent()) {
            field(context, "note", "{procurement.rejectionReason}");
            valid = false;
        }
        if (value instanceof CreatePurchaseOrderRequest po && po.currency() != null) {
            Currency currency;
            try {
                currency = Currency.getInstance(po.currency());
                if (currency.getDefaultFractionDigits() < 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException unknown) {
                field(context, "currency", "{procurement.currency}");
                return false;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; po.lines() != null && i < po.lines().size(); i++) {
                var line = po.lines().get(i);
                if (line == null || line.unitPrice() == null) continue;
                if (line.unitPrice().stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
                    context.buildConstraintViolationWithTemplate("{procurement.priceScale}")
                            .addPropertyNode("lines").addPropertyNode("unitPrice").inIterable().atIndex(i)
                            .addConstraintViolation();
                    valid = false;
                }
                // Field constraints report these values. Do not add unbounded exponents to a
                // BigDecimal total before those constraints have rejected the individual price.
                if (line.quantityOrdered() <= 0 || line.quantityOrdered() > 1_000_000
                        || line.unitPrice().signum() < 0 || line.unitPrice().scale() > 2
                        || line.unitPrice().compareTo(new BigDecimal("9999999999999999.99")) > 0) continue;
                total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantityOrdered())));
            }
            if (total.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
                field(context, "lines", "{procurement.totalAmount}");
                valid = false;
            }
        }
        return valid;
    }

    private static void field(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
    }
}
