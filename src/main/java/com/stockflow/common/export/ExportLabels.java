package com.stockflow.common.export;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.i18n.Messages;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * The lines every export carries under its title, in the reader's language ({@code Accept-Language}, Vietnamese by
 * default, the {@code export.*} keys of the message bundles): what was filtered - a file read a week later must say
 * which customers and which days it covers - and when it was exported, since a report of today's figures is only true
 * the day it was taken. Dates as the business reads them, {@code dd/MM/yyyy}, in Vietnam time.
 */
public final class ExportLabels {

    /** How a day is written in a title line. */
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private ExportLabels() {
    }

    /** "Filter: a; b", or "Filter: none" when {@code parts} has none (null parts are skipped). */
    public static String filterLine(Messages messages, List<String> parts) {
        List<String> given = parts.stream().filter(Objects::nonNull).toList();
        return messages.get("export.filter", "Filter: {0}", given.isEmpty()
                ? messages.get("export.filter.none", "none") : String.join("; ", given));
    }

    /** "Exported at 10:30 15/11/2026". */
    public static String exportedAt(Messages messages, Instant at) {
        return messages.get("export.exported-at", "Exported at {0}", MOMENT.format(at.atZone(BusinessCalendar.ZONE)));
    }

    /** A day as a title line writes it. */
    public static String day(LocalDate day) {
        return DAY.format(day);
    }

    /** {@code <stem>-yyyyMMdd-HHmm.xlsx} in Vietnam time: two exports of one day do not overwrite each other. */
    public static String filename(String stem, Instant at) {
        return stem + "-" + STAMP.format(at.atZone(BusinessCalendar.ZONE)) + ".xlsx";
    }
}
