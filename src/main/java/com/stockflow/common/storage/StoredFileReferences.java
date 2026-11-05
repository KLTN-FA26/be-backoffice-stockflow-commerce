package com.stockflow.common.storage;

import java.util.Collection;
import java.util.Set;

/**
 * Which stored objects a module's records still name - the question {@link OrphanedFileSweeper} asks before it deletes
 * anything. A module implements this for each category whose files it owns; a category nobody claims is never swept.
 *
 * <p>Implemented in the module's repository layer with one query per call ({@code WHERE key IN (...)}): the sweeper
 * asks in batches, never row by row.</p>
 */
public interface StoredFileReferences {

    /** The categories whose objects this module's records name. */
    Set<FileCategory> categories();

    /**
     * Of {@code keys} - all of {@code category}, one of {@link #categories()} - those a record of this module still
     * names. A key it does not know about is simply left out.
     */
    Set<String> referenced(FileCategory category, Collection<String> keys);
}
