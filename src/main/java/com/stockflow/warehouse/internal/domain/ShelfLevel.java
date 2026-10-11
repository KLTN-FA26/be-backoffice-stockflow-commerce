package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One level of a shelf. A child of {@link Shelf}; {@code levelIndex} is part of every bin's
 * location code, so it never changes and levels are never renumbered.
 */
public final class ShelfLevel {

    private final UUID id;
    private final int levelIndex;
    private LevelMeasures measures;
    private final List<Bin> bins;

    public ShelfLevel(UUID id, int levelIndex, LevelMeasures measures, List<Bin> bins) {
        this.id = Objects.requireNonNull(id, "id");
        this.levelIndex = levelIndex;
        this.measures = Objects.requireNonNull(measures, "measures");
        this.bins = new ArrayList<>(bins);
    }

    public Bin bin(UUID binId) {
        return bins.stream().filter(bin -> bin.id().equals(binId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BIN_NOT_FOUND,
                        "Bin %s is not on level %d".formatted(binId, levelIndex)));
    }

    void add(Bin bin) {
        bins.add(bin);
    }

    void apply(LevelMeasures measures) {
        this.measures = Objects.requireNonNull(measures, "measures");
    }

    public UUID id() { return id; }
    public int levelIndex() { return levelIndex; }
    public LevelMeasures measures() { return measures; }
    public List<Bin> bins() { return Collections.unmodifiableList(bins); }
}
