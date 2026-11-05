package com.stockflow.assistant.internal.controller.dto;

import java.util.List;

/**
 * A spoken question written down (ADR-0015): for the person who spoke to read, correct and send as their question.
 * Nothing was kept and nothing was asked yet. Show {@code text} as plain text in the question box, and mark the
 * {@code codes} and {@code numbers} in it: a misheard digit names another real carton, lot or order, so those are what
 * to check against the label, or to scan instead of saying.
 *
 * @param text    what was heard; empty when nothing was
 * @param codes   the codes written in it (SKUs, order, lot and location numbers, carton labels), each once, in order
 * @param numbers the other words holding a digit, each once, in order
 * @param seconds the recording's length
 */
public record TranscriptionResponse(String text, List<String> codes, List<String> numbers, double seconds) {
}
