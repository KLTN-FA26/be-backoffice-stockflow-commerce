package com.stockflow.procurement.internal.controller.dto;

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
        return valid;
    }

    private static void field(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
    }
}
