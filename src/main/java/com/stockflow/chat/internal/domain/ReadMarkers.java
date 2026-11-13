package com.stockflow.chat.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How far each participant has read a conversation ({@code chat.participant_read}). A marker only moves forward: a
 * second tab that reports an older position, or a retried request, never unreads anything.
 */
public interface ReadMarkers {

    /** One reader's position: the sequence of the last message they saw. */
    record Marker(UUID userId, SenderType readerType, long seq, Instant at) {
    }

    /**
     * Moves the reader's marker to {@code seq}, or leaves it where it is when it is already there or further.
     *
     * @return the marker as it now stands
     */
    Marker advance(UUID conversationId, UUID userId, SenderType readerType, long seq, Instant at);

    List<Marker> of(UUID conversationId);
}
