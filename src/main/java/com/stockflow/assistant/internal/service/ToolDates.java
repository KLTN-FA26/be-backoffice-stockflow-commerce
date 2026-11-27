package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * A calendar day a tool takes as input, written {@code YYYY-MM-DD} as the calendar in the question context writes it.
 *
 * <p>A day, never an instant: which instants it covers is the business's time zone's to say, and the {@code api}
 * methods the tools pass it to already count in Asia/Ho_Chi_Minh ({@code ListOrdersQuery}, the reports). A malformed
 * day is a {@code VALIDATION_FAILED} the model corrects, like any other argument ({@link ToolArguments}).</p>
 */
final class ToolDates {

    private ToolDates() {
    }

    /** The day named {@code name}; null when it is absent or an empty string. */
    static LocalDate optional(ToolArguments arguments, String name) {
        String text = arguments.optionalText(name, 32);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    name + " must be a day written YYYY-MM-DD, or an empty string");
        }
    }
}
